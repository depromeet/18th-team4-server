#!/usr/bin/env bash
# readum blue/green 배포 스크립트. 원본은 repo 의 infra/scripts/deploy.sh 이고,
# 서버의 /opt/readum/bin/deploy.sh 로 동기화해 사용한다 (전체 구조는 docs/ops/infrastructure.md).
#
# 사용법:
#   deploy.sh deploy <jar 경로>   # 비활성 색에 jar 를 올리고 기동 → readiness 통과 → 트래픽 전환 → 구 프로세스 종료
#   deploy.sh rollback            # 반대 색(직전 버전 jar 보유)을 재기동해 트래픽을 되돌림
#   deploy.sh status              # 활성 색·양쪽 유닛 상태·readiness 를 출력
#
# 설계 원칙:
# - "지금 어느 색이 활성인가"의 유일한 판단 근거는 nginx upstream 파일이다.
#   별도 상태 파일을 두면 nginx 실제 라우팅과 어긋나는 두 번째 기록이 생긴다.
# - readiness 통과 전에는 트래픽에 손대지 않는다. 신 프로세스 기동 실패 시 기존 서비스는 무영향.
# - 구 프로세스 종료는 systemctl stop → 앱의 graceful shutdown(진행 중 요청 60초 대기)에 맡긴다.
set -euo pipefail

BASE_DIR="/opt/readum"
RELEASES_DIR="$BASE_DIR/releases"
UPSTREAM_CONF="/etc/nginx/conf.d/readum-upstream.conf"
NGINX_SYNC_DIR="$BASE_DIR/nginx"
# CI 가 NGINX_SYNC_DIR 에 올려두는 nginx 설정 원본(repo infra/nginx)과 실제 적용 경로의 짝. "파일명:적용경로"
# 적용 경로를 바꾸면 sudoers(infra/sudoers/readum-deploy)의 tee 허용 경로도 함께 바꿔야 한다.
NGINX_CONF_TARGETS=(
  "app.conf:/etc/nginx/sites-available/app.conf"
  "websocket-upgrade.conf:/etc/nginx/conf.d/websocket-upgrade.conf"
)
BLUE_PORT=8081
GREEN_PORT=8082
READINESS_TIMEOUT_SECONDS=90
RELEASE_KEEP_COUNT=5

log() { echo "==> $*"; }
fail() { echo "!! $*" >&2; exit 1; }

port_of() {
  case "$1" in
    blue) echo "$BLUE_PORT" ;;
    green) echo "$GREEN_PORT" ;;
    *) fail "알 수 없는 색: $1" ;;
  esac
}

other_of() {
  case "$1" in
    blue) echo "green" ;;
    green) echo "blue" ;;
  esac
}

# upstream 파일에서 활성 포트를 읽어 색을 판정한다. 파일이 없으면(최초 전환 전) 빈 문자열.
active_color() {
  [[ -f "$UPSTREAM_CONF" ]] || { echo ""; return; }
  local port
  port="$(grep -oE '127\.0\.0\.1:[0-9]+' "$UPSTREAM_CONF" | cut -d: -f2 || true)"
  case "$port" in
    "$BLUE_PORT") echo "blue" ;;
    "$GREEN_PORT") echo "green" ;;
    *) fail "upstream 파일($UPSTREAM_CONF)에서 활성 포트를 판정할 수 없음: '$port'" ;;
  esac
}

wait_readiness() {
  local port="$1"
  log "readiness 대기 (127.0.0.1:$port, 최대 ${READINESS_TIMEOUT_SECONDS}초)"
  for ((i = 1; i <= READINESS_TIMEOUT_SECONDS; i++)); do
    if curl -fsS "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      log "readiness 통과 (${i}초)"
      return 0
    fi
    sleep 1
  done
  return 1
}

# CI 가 올려둔 nginx 설정을 실제 경로와 비교해 달라진 파일만 반영한다.
# 반영 후 nginx -t 실패 시 이전 내용으로 복구하고 배포를 중단한다 (reload 전이므로 트래픽 무영향).
sync_nginx_conf() {
  local entry name src dst backup
  local applied=()
  for entry in "${NGINX_CONF_TARGETS[@]}"; do
    name="${entry%%:*}"; dst="${entry#*:}"
    src="$NGINX_SYNC_DIR/$name"
    [[ -f "$src" ]] || continue   # 서버에서 수동 실행 등 원본이 없으면 건너뜀
    cmp -s "$src" "$dst" 2>/dev/null && continue
    backup="$NGINX_SYNC_DIR/$name.prev"
    [[ -f "$dst" ]] && cat "$dst" > "$backup"
    log "nginx 설정 갱신: $dst"
    sudo /usr/bin/tee "$dst" < "$src" >/dev/null
    applied+=("$backup:$dst")
  done
  [[ ${#applied[@]} -gt 0 ]] || return 0
  if ! sudo /usr/sbin/nginx -t; then
    log "nginx 설정 검증 실패 — 이전 설정으로 복구"
    for entry in "${applied[@]}"; do
      backup="${entry%%:*}"; dst="${entry#*:}"
      [[ -f "$backup" ]] && sudo /usr/bin/tee "$dst" < "$backup" >/dev/null
    done
    fail "새 nginx 설정이 검증에 실패해 이전 설정으로 복구함. 배포 중단 (트래픽은 기존 프로세스 유지)"
  fi
  sudo /usr/bin/systemctl reload nginx
}

switch_traffic_to() {
  local port="$1"
  log "nginx upstream 을 127.0.0.1:$port 로 전환"
  printf 'upstream readum_backend { server 127.0.0.1:%s; }\n' "$port" \
    | sudo /usr/bin/tee "$UPSTREAM_CONF" >/dev/null
  sudo /usr/sbin/nginx -t
  sudo /usr/bin/systemctl reload nginx
}

# 대상 색에 기동 → readiness → 전환 → 반대 색 종료. deploy 와 rollback 이 공유하는 본체.
activate() {
  local target="$1"
  local target_port old
  target_port="$(port_of "$target")"
  old="$(other_of "$target")"

  [[ -f "$BASE_DIR/$target/app.jar" ]] || fail "$BASE_DIR/$target/app.jar 이 없음"

  # 이전 배포 잔재가 떠 있으면 내리고 새로 기동한다.
  sudo /usr/bin/systemctl stop "readum-$target" 2>/dev/null || true
  log "readum-$target 기동"
  sudo /usr/bin/systemctl start "readum-$target"

  if ! wait_readiness "$target_port"; then
    sudo /usr/bin/systemctl stop "readum-$target" || true
    fail "readum-$target 이 ${READINESS_TIMEOUT_SECONDS}초 안에 readiness 를 통과하지 못함. 트래픽은 기존 프로세스에 그대로 남아 있음. 로그: journalctl -u readum-$target"
  fi

  switch_traffic_to "$target_port"

  # 재부팅 시 활성 색만 자동 기동되도록 enable 을 활성 색으로 맞춘다.
  sudo /usr/bin/systemctl enable "readum-$target" >/dev/null 2>&1 || true
  sudo /usr/bin/systemctl disable "readum-$old" >/dev/null 2>&1 || true

  # 구 프로세스는 graceful 종료 (앱이 진행 중 요청을 최대 60초 마무리, systemd 는 75초 대기 후 강제 종료).
  if systemctl is-active --quiet "readum-$old"; then
    log "readum-$old graceful 종료"
    sudo /usr/bin/systemctl stop "readum-$old"
  fi

  log "완료: $target($target_port) 활성"
}

cmd_deploy() {
  local jar="${1:-}"
  [[ -n "$jar" && -f "$jar" ]] || fail "사용법: deploy.sh deploy <jar 경로>"

  sync_nginx_conf

  local active target
  active="$(active_color)"
  # 최초 전환(cutover) 전에는 upstream 파일이 없다 → blue 부터 시작.
  target="$([[ -n "$active" ]] && other_of "$active" || echo "blue")"
  log "활성: ${active:-없음} → 배포 대상: $target"

  cp "$jar" "$BASE_DIR/$target/app.jar"
  activate "$target"

  # 오래된 릴리스 정리 (최근 N개 유지).
  ls -1t "$RELEASES_DIR"/*.jar 2>/dev/null | tail -n +$((RELEASE_KEEP_COUNT + 1)) | xargs -r rm -f
}

cmd_rollback() {
  local active target
  active="$(active_color)"
  [[ -n "$active" ]] || fail "upstream 파일이 없어 활성 색을 알 수 없음. rollback 불가."
  target="$(other_of "$active")"
  log "rollback: $active → $target (직전 버전 jar 로 재기동)"
  activate "$target"
}

cmd_status() {
  local active
  active="$(active_color)"
  echo "활성 색: ${active:-없음 (upstream 파일 없음)}"
  for color in blue green; do
    local state port
    state="$(systemctl is-active "readum-$color" 2>/dev/null || true)"
    port="$(port_of "$color")"
    local ready="-"
    if curl -fsS "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      ready="UP"
    fi
    echo "readum-$color (:$port) — unit=$state, readiness=$ready"
  done
}

case "${1:-}" in
  deploy) shift; cmd_deploy "$@" ;;
  rollback) cmd_rollback ;;
  status) cmd_status ;;
  *) fail "사용법: deploy.sh {deploy <jar 경로>|rollback|status}" ;;
esac
