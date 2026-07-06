# 배포 구조와 절차 (blue/green)

> 적용 대상: 개발 서버(운영 겸용) 배포. 관련 이슈 #108, Epic #107.
> 인프라 파일의 원본은 repo 의 [`infra/`](../../infra) 디렉토리다. 서버에 직접 적용된 상태가 repo 와 다르면 repo 를 고쳐서 배포하는 것이 원칙이다.

## 개요

단일 EC2 에서 같은 앱을 두 systemd 유닛(blue :8081 / green :8082)으로 번갈아 띄우고, nginx 의 upstream 파일 하나를 갈아끼워 트래픽을 전환한다. 완전 무중단이 아니라 **다운타임을 수십 ms(nginx reload) 수준으로 줄이는 절충안**이다.

```text
Internet ── nginx(:443, TLS 종료)
              │  proxy_pass http://readum_backend
              ▼
  /etc/nginx/conf.d/readum-upstream.conf   ← 배포 스크립트가 갱신하는 "전환 스위치"
    upstream readum_backend { server 127.0.0.1:8081; }
              │
     ┌────────┴────────┐
  readum-blue(:8081)  readum-green(:8082)   ← systemd 유닛. 평상시 한쪽만 상주
```

| 구성물 | 위치 (서버) | 원본 (repo) |
|---|---|---|
| nginx server 블록 | `/etc/nginx/sites-available/app.conf` | `infra/nginx/app.conf` |
| 전환 스위치(upstream) | `/etc/nginx/conf.d/readum-upstream.conf` | 없음 — 런타임 상태라 repo 로 관리하지 않음. `deploy.sh` 가 생성·갱신 |
| systemd 유닛 | `/etc/systemd/system/readum-{blue,green}.service` | `infra/systemd/` |
| 배포 스크립트 | `/opt/readum/bin/deploy.sh` | `infra/scripts/deploy.sh` |
| jar | `/opt/readum/{blue,green}/app.jar`, 보관은 `/opt/readum/releases/` (최근 5개) | CI 가 빌드해 업로드 |
| 환경 변수 | `/opt/readum/.env` | 원본은 GitHub Actions Secrets 의 `ENV_FILE` — CI 가 배포 때마다 서버 `.env` 를 재작성한다(아래 CI/CD 절). 변경 절차 = Secret 수정 → 재배포. **`SERVER_PORT` 를 넣지 말 것** (유닛의 포트 지정을 덮어씀) |
| Redis 컨테이너 | `docker compose` (readum-redis) | `infra/docker/docker-compose.dev.yml` (개발 서버 전용 — 로컬 개발용 아님) |
| sudo 권한 목록 | `/etc/sudoers.d/readum-deploy` | `infra/sudoers/readum-deploy` |

## 배포 흐름

배포 한 번은 다음 순서로 진행된다 (`deploy.sh deploy <jar>` 가 수행):

1. upstream 파일에서 활성 색을 읽는다 → 반대 색이 배포 대상
2. jar 를 대상 색 디렉토리에 복사하고 `systemctl start`
3. `/actuator/health/readiness` 가 통과할 때까지 대기 (최대 90초) — **실패하면 트래픽에 손대지 않고 중단** (기존 서비스 무영향)
4. upstream 파일 재작성 → `nginx -t` → `systemctl reload nginx` — 이 순간 새 요청이 새 프로세스로 감. 진행 중이던 요청·SSE 는 기존 연결로 계속 처리됨
5. 구 프로세스 `systemctl stop` — 앱의 graceful shutdown 이 진행 중 요청을 최대 60초 마무리 (`application.yml` 의 `server.shutdown` / `spring.lifecycle.timeout-per-shutdown-phase`, systemd 는 75초 후 강제 종료)

수동 명령 (서버에서):

```bash
/opt/readum/bin/deploy.sh status                       # 활성 색·양쪽 상태 확인
/opt/readum/bin/deploy.sh deploy /opt/readum/releases/readum-<sha>.jar
/opt/readum/bin/deploy.sh rollback                     # 직전 버전(반대 색 jar)으로 되돌리기
```

롤백은 "반대 색을 재기동해 트래픽을 되돌리는 것"이다. 메모리 제약 때문에 구 프로세스를 상시 살려두지 않으므로, 롤백도 기동 → readiness → 전환의 같은 사이클을 탄다 (약 1분).

## CI/CD (GitHub Actions)

`.github/workflows/deploy.yml` 하나가 빌드와 배포를 수행한다.

- **트리거**: 수동 실행(workflow_dispatch). dev 머지 자동 배포(push 트리거)는 최초 전환 검증이 끝난 뒤 워크플로우의 주석을 풀어 켠다.
- **하는 일**: 전체 테스트 포함 빌드(테스트 통과 = 배포 자격) → jar 에 커밋 식별자 부여(`readum-<short sha>.jar`) → 서버 `.env` 재작성(원본 = `ENV_FILE` secret) → jar·deploy.sh 업로드 → `deploy.sh deploy` 실행.
- **하지 않는 일**: nginx 설정·systemd 유닛 변경 반영 — root 권한이 필요해 CI 에는 주지 않는다. `infra/nginx/`·`infra/systemd/`·sudoers 가 바뀌면 setup.sh 재실행과 위 nginx 교체 절차를 수동으로 수행한다.
- 동시 배포는 concurrency 설정으로 직렬화된다 (겹치면 뒤 실행이 대기).

필요한 Repository Secrets (Settings → Secrets and variables → Actions):

| 이름 | 내용 |
|---|---|
| `DEPLOY_HOST` | 서버 주소 (탄력적 IP 또는 도메인) |
| `DEPLOY_SSH_KEY` | 배포 전용 SSH 개인키 — 공개키를 서버 ubuntu 계정 `~/.ssh/authorized_keys` 에 등록 |
| `ENV_FILE` | `/opt/readum/.env` 전문. **이 Secret 이 환경변수의 원본** — 값을 바꾸려면 Secret 수정 후 재배포. Secrets 는 재열람이 안 되므로 팀 비밀 저장소에 사본 유지 |

## 배포 수칙

- **05:50~06:10 에는 배포를 피한다.** 오전 6시 감상문 적재 스캔·디스패치와 배포의 2-프로세스 구간이 겹치면 스캔과 외부 API(OpenAI) 호출 예산이 잠깐 2배가 된다. 데이터는 멱등이라 안전하지만 호출 한도가 뚫릴 수 있다. 스크립트가 강제하지 않으므로 배포하는 사람이 시간대를 확인한다. rate limiter 상태가 Redis 로 공유되면(#88) 이 수칙은 사실상 불필요해진다.
- 전환 구간에는 JVM 2개(각 `-Xmx256m`)가 동시에 떠 있으므로 서버 메모리에 여유가 필요하다. 스왑 2GB 를 유지하고, 배포 중 `free -m` 스왑 피크가 계속 커지면 인스턴스 증설을 검토한다.

## 최초 전환(cutover) 절차 — 1회성

기존 "단일 프로세스(:8080) + 수동 deploy.sh" 체계에서 새 체계로 갈아타는 절차. 이 이사 자체가 첫 blue/green 전환이라 순서만 지키면 다운타임이 없다 (인스턴스 증설 정지 1회 제외).

**선행 (콘솔, 계획 정지 1회 — 팀 공지 후):**

1. 탄력적 IP 확인 — 없으면 할당·연결하고 DNS(readum.kr, api.readum.kr)를 새 IP 로 갱신
2. 인스턴스 증설 (t3.micro → t3.small 권장): stop → 타입 변경 → start
3. 스왑 2GB 로 증설:
   ```bash
   sudo swapoff -a && sudo rm -f /swapfile
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
   sudo mkswap /swapfile && sudo swapon /swapfile
   # /etc/fstab 의 스왑 항목도 /swapfile 2G 기준으로 갱신
   ```

**서버 준비 (기존 :8080 프로세스는 계속 서비스 중 — 무영향):**

4. repo 를 임시로 받아 `sudo ./infra/scripts/setup.sh <repo>/infra` 실행 (디렉토리·유닛·sudoers 설치)
5. 기존 `.env`(현행 배포가 쓰는 `~/18th-team4-server/.env`)를 새 위치로 복사 — 9단계에서 기존 디렉토리를 지우므로 반드시 그 전에:
   ```bash
   cp ~/18th-team4-server/.env /opt/readum/.env
   chmod 600 /opt/readum/.env
   vi /opt/readum/.env   # REDIS_PASSWORD=<openssl rand -base64 24> 추가, SERVER_PORT/APP_PORT 줄 제거
   ```
   같은 내용을 GitHub Actions Secrets(CI 의 `.env` 재작성용)에도 등록한다. Secrets 는 등록 후 다시 열람할 수 없으므로 팀 비밀 저장소에 사본을 함께 남긴다
6. Docker 설치 후 Redis 기동:
   ```bash
   docker compose --env-file /opt/readum/.env -f <repo>/infra/docker/docker-compose.dev.yml up -d
   ```

**전환:**

7. CI(workflow_dispatch) 또는 로컬 빌드로 jar 를 `/opt/readum/releases/` 에 올리고 `deploy.sh deploy <jar>` 실행 — upstream 파일이 없으므로 blue 로 기동·전환된다. 단, 이 시점 nginx 는 아직 :8080 을 가리키는 구 설정이므로 전환은 다음 단계에서 완성된다
8. nginx 설정 교체: 기존 백업 후 repo 판 적용 → 검사 → reload
   ```bash
   sudo cp /etc/nginx/sites-available/app.conf /etc/nginx/sites-available/app.conf.bak
   sudo cp <repo>/infra/nginx/app.conf /etc/nginx/sites-available/app.conf
   sudo nginx -t && sudo systemctl reload nginx
   ```
9. 구 :8080 프로세스 종료 (`kill -TERM $(cat ~/18th-team4-server/app.pid)`) 후 기존 clone 디렉토리(`~/18th-team4-server`) 정리
10. 검증(아래) 통과 후, `.github/workflows/deploy.yml` 의 push 트리거 주석을 풀어 dev 머지 자동 배포 활성화

## 배포 검증 체크리스트

cutover 직후와 배포 방식이 바뀔 때마다 수행:

- [ ] 같은 커밋으로 재배포를 한 번 더 실행해 정규 사이클(활성 판정 → 반대 색 기동 → readiness → 전환 → 구 프로세스 graceful 종료) 전체를 확인
- [ ] 재배포 도중 1초 간격 `curl https://api.readum.kr/...` 실패 0건
- [ ] 재배포 도중 열어둔 AI 채팅 SSE 스트림이 끊기지 않음
- [ ] 외부에서 `curl -i https://api.readum.kr/actuator/prometheus` → 404 (swagger-ui, v3/api-docs 도 동일)
- [ ] 앱 로그의 `X-Forwarded-For` 에 실제 클라이언트 IP 가 보존됨
- [ ] 재배포 중 `free -m` 스왑 피크 기록 (다음 배포의 기준선)
