#!/usr/bin/env bash
# readum 서버 1회성 준비 스크립트 (멱등 — 여러 번 실행해도 안전).
# 새 서버 세팅이나 기존 서버의 blue/green 전환 준비에 사용한다. sudo 로 실행:
#   sudo ./setup.sh <repo 의 infra 디렉토리 경로>
#
# 이 스크립트가 하는 일: 디렉토리 구조 / systemd 유닛 / sudoers 설치.
# 하지 않는 일(운영 트래픽에 영향을 주는 것들 — docs/ops/deployment.md 의 절차로 수동 수행):
#   - nginx 설정 교체와 reload (전환 시점을 사람이 정해야 함)
#   - Docker·Redis 설치와 기동
#   - 스왑 증설
set -euo pipefail

INFRA_DIR="${1:-}"
[[ -n "$INFRA_DIR" && -d "$INFRA_DIR/systemd" ]] || {
  echo "사용법: sudo ./setup.sh <repo 의 infra 디렉토리 경로>" >&2
  exit 1
}
[[ "$(id -u)" -eq 0 ]] || { echo "sudo 로 실행해야 한다" >&2; exit 1; }

echo "==> /opt/readum 디렉토리 구조"
mkdir -p /opt/readum/{blue,green,releases,bin}
chown -R ubuntu:ubuntu /opt/readum

echo "==> systemd 유닛 설치"
cp "$INFRA_DIR"/systemd/readum-blue.service /etc/systemd/system/
cp "$INFRA_DIR"/systemd/readum-green.service /etc/systemd/system/
systemctl daemon-reload

echo "==> 배포·백업 스크립트 설치"
install -m 0755 -o ubuntu -g ubuntu "$INFRA_DIR"/scripts/deploy.sh /opt/readum/bin/deploy.sh
install -m 0755 -o ubuntu -g ubuntu "$INFRA_DIR"/scripts/backup-mysql.sh /opt/readum/bin/backup-mysql.sh

echo "==> MySQL 설정 배치"
mkdir -p /opt/readum/mysql /opt/readum/mysql-data /opt/readum/mysql-backup
install -m 0644 -o ubuntu -g ubuntu "$INFRA_DIR"/mysql/my.cnf /opt/readum/mysql/my.cnf
chown ubuntu:ubuntu /opt/readum/mysql /opt/readum/mysql-backup

echo "==> sudoers 설치 (ubuntu 가 배포에 필요한 명령만 비밀번호 없이 실행)"
visudo -cf "$INFRA_DIR"/sudoers/readum-deploy
install -m 0440 -o root -g root "$INFRA_DIR"/sudoers/readum-deploy /etc/sudoers.d/readum-deploy

echo "==> 완료. 다음 단계(.env 배치, nginx 전환, Docker/Redis)는 docs/ops/deployment.md 의 절차를 따른다."
