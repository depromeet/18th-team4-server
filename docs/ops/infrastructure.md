# 인프라 구성

> 이 문서는 코드로 확인할 수 없는 콘솔 설정 세부(보안 그룹 규칙, 버킷 목록 등)는 담지 않는다.
> 그런 값은 문서가 금방 낡아 거짓이 되므로 AWS 콘솔을 원본으로 본다 (2026-07-05 결정).
> 인프라 파일(nginx·systemd·배포 스크립트·컨테이너 정의)의 원본은 repo 의 [`infra/`](../../infra) 디렉토리다.
> 서버에 직접 적용된 상태가 repo 와 다르면 repo 를 고쳐서 반영하는 것이 원칙이다.

## 구성도

2026-07-06 부터 단일 EC2 에 전부 상주한다 (RDS 는 Docker MySQL 로 이관 후 폐기 — 최종 스냅샷 보존).

개발(dev)과 운영(prod)이 **같은 EC2 인스턴스에 공존**한다. 도메인·포트·systemd 유닛·nginx upstream·MySQL/Redis 컨테이너·환경 파일이 모두 분리되어, 되돌릴 수 없는 데이터 계층까지 격리한다. (트래픽이 실재하면 인스턴스 증설 또는 운영 분리 — 아래 "운영 서버 공존" 참조.)

```text
                        Internet
                            │
      ┌─────────────┬───────┴───────────┬──────────────────┐
      │             │                   │                  │
┌─────▼──────┐ ┌────▼────────┐   ┌──────▼──────────┐        │
│ readum.kr  │ │api.readum.kr│   │prod-api.readum.kr        │
│ 프론트엔드    │ │ 개발 API     │   │ 운영 API          │        │
│ (CloudFront)│ │ HTTPS :443  │   │ HTTPS :443       │        │
└────────────┘ └────┬────────┘   └──────┬──────────┘        │
                    │                   │                   │
        ┌───────────▼───────────────────▼───────────────────▼──┐
        │  EC2 t3.small · Ubuntu · ap-northeast-2 · 스왑 2GB     │
        │  nginx (:80→:443, TLS 종료) — server 블록 2개(도메인별)  │
        │                                                       │
        │  개발: upstream readum_backend                         │
        │    readum-blue(:8081) / readum-green(:8082)  한쪽만 상주 │
        │  운영: upstream readum_prod_backend                    │
        │    readum-prod-blue(:8083) / readum-prod-green(:8084)  │
        │                                                       │
        │  Docker (전부 127.0.0.1 바인딩):                        │
        │    개발: readum-mysql(:3306)      · readum-redis(:6379) │
        │    운영: readum-prod-mysql(:3307) · readum-prod-redis(:6380)
        │    공용: readum-slack-receiver(:8090)                  │
        └───────────────────────────────────────────────────────┘
```

## 배포 (blue/green)

같은 앱을 blue(:8081)/green(:8082) 두 systemd 유닛으로 번갈아 띄우고, nginx 의 upstream 파일 하나를 갈아끼워 트래픽을 전환한다. 다운타임은 nginx reload 수준(수십 ms)이다.

빌드와 배포 실행은 GitHub Actions([`deploy.yml`](../../.github/workflows/deploy.yml))가 수행한다 — CI 흐름 자체는 그 파일이 원본이고, 여기는 서버 쪽 구조만 적는다.

| 구성물 | 위치 (서버) | 원본 |
|---|---|---|
| nginx server 블록 | `/etc/nginx/sites-available/app.conf` | `infra/nginx/app.conf` — CI 가 배포 때마다 `/opt/readum/nginx/` 로 올리고, `deploy.sh` 의 `sync_nginx_conf` 가 변경분만 반영(검증 실패 시 이전 설정 복구 + 배포 중단) |
| nginx WebSocket map | `/etc/nginx/conf.d/websocket-upgrade.conf` | `infra/nginx/websocket-upgrade.conf` — app.conf 가 쓰는 `$connection_upgrade` 를 정의하는 map. map 은 http 컨텍스트에만 둘 수 있어 server 블록 밖 conf.d 조각으로 분리. 없으면 nginx 기동 실패. 반영 방식은 app.conf 와 동일(CI 업로드 + `sync_nginx_conf`) |
| 전환 스위치(upstream) | `/etc/nginx/conf.d/readum-upstream.conf` | 없음 — "지금 어느 색이 활성인가"라는 런타임 상태. `deploy.sh` 가 생성·갱신하며 활성 색 판정도 이 파일에서 읽는다 |
| systemd 유닛 | `/etc/systemd/system/readum-{blue,green}.service` | `infra/systemd/` |
| 배포 스크립트 | `/opt/readum/bin/deploy.sh` | `infra/scripts/deploy.sh` (CI 가 배포 때마다 동기화) |
| jar | `/opt/readum/{blue,green}/app.jar`, 보관 `/opt/readum/releases/` (최근 5개) | CI 가 빌드·업로드 |
| 환경 변수 | `/opt/readum/.env` | GitHub Secret `ENV_FILE` — CI 가 배포 때마다 재작성. 변경 = Secret 수정 → 재배포. **`SERVER_PORT` 금지**(유닛의 포트 지정을 덮어씀) |
| MySQL 설정 | `/opt/readum/mysql/my.cnf` | `infra/mysql/my.cnf` — CI 가 배포 때마다 최신본을 올린다(파일만). 실제 반영은 컨테이너 재시작(수동) |
| 컨테이너 정의 | `/opt/readum/docker-compose.dev.yml` | `infra/docker/docker-compose.dev.yml` — CI 가 배포 때마다 올린다(로컬 개발용은 `docker-compose.local.yml`). MySQL·Redis 기동·재시작은 수동(`docker compose ... up -d`) — 상태 서비스라 코드 배포로 재시작하지 않는다 |
| sudo 권한 | `/etc/sudoers.d/readum-deploy` | `infra/sudoers/readum-deploy` |

서버에서 직접 쓰는 명령:

```bash
/opt/readum/bin/deploy.sh dev status     # 활성 색·양쪽 유닛 상태·readiness (운영은 dev 대신 prod)
/opt/readum/bin/deploy.sh dev rollback   # 직전 버전(반대 색 jar)으로 되돌리기 (~1분)
```

> `deploy.sh` 는 개발·운영 공용이다. 첫 인자로 환경(`dev`|`prod`)을 받아 포트·유닛 접두사·경로·upstream 을 고른다. 두 환경은 이 값들만 다르고 로직은 공유한다.

배포 수칙:

- ~~**05:50~06:10 배포 회피**~~ (2026-07 해소) — 6시 감상문 적재 스캔과 전환 구간(두 프로세스 동시 상주)이 겹치면 외부 API(OpenAI) 호출 예산이 잠깐 2배가 됐었다. 전역 게이트(Redis) 도입으로 호출 예산이 프로세스 간 공유되어 이 회피는 불필요해졌다.
- 전환 구간엔 JVM 2개(각 `-Xmx256m`)가 동시에 뜬다. 배포 중 스왑 피크가 계속 커지면 인스턴스 증설을 검토한다.
- nginx 설정(`app.conf`·`websocket-upgrade.conf`)은 배포 파이프라인이 반영한다 — sudo 는 고정 경로 `tee` 두 줄만 추가로 허용(sudoers 에 명시). systemd 유닛 설치·sudoers 갱신은 root 권한이라 CI 가 직접 실행하지 않는다(root 권한 불필요 원칙). 대신 CI(`deploy.yml`)가 배포 때마다 `infra/` 전체를 `/opt/readum/infra` 로 배달하므로, 서버 git 체크아웃 없이 `sudo /opt/readum/infra/scripts/setup.sh /opt/readum/infra` 로 반영한다. sudoers 갱신 전에는 nginx 설정 자동 반영이 sudo 거부로 실패하므로, 이 구조를 처음 켜거나 유닛·sudoers 가 바뀌면 setup.sh 재실행이 선행돼야 한다.

새 서버를 처음 준비할 때도 같은 `setup.sh` 가 시작점이다 (디렉토리·유닛·sudoers·MySQL 설정 배치 — 개발·운영 양쪽). CI 가 `/opt/readum/infra` 를 최신화해 두므로 서버에 repo 를 clone 할 필요가 없다.

## 운영 서버 공존 (prod)

운영은 개발과 **같은 인스턴스**에 나란히 올라가되, 아래가 전부 분리된다. 로직(blue/green 전환·nginx 반영·graceful 종료)은 `deploy.sh` 하나를 공유하고, 첫 인자 `dev|prod` 로 갈린다.

| 항목 | 개발(dev) | 운영(prod) |
|---|---|---|
| 도메인 | api.readum.kr | prod-api.readum.kr |
| 트리거 브랜치 | `dev` ([`deploy.yml`](../../.github/workflows/deploy.yml)) | `release` ([`deploy-prod.yml`](../../.github/workflows/deploy-prod.yml)) |
| blue/green 포트 | 8081 / 8082 | 8083 / 8084 |
| systemd 유닛 | `readum-{blue,green}` | `readum-prod-{blue,green}` |
| nginx server 블록 | `app.conf` | `prod-app.conf` |
| upstream | `readum_backend` / `readum-upstream.conf` | `readum_prod_backend` / `readum-prod-upstream.conf` |
| 색·릴리스 경로 | `/opt/readum/{blue,green,releases}` | `/opt/readum/prod/{blue,green,releases}` |
| 환경 파일 | `/opt/readum/.env` (Secret `ENV_FILE`) | `/opt/readum/prod.env` (Secret `PROD_ENV_FILE`) |
| MySQL | readum-mysql :3306 (버퍼 256M·perf_schema ON) | readum-prod-mysql :3307 (버퍼 128M·perf_schema OFF) |
| Redis | readum-redis :6379 | readum-prod-redis :6380 |
| Swagger | 공개 | 비공개(`application-prod.yml` 에서 springdoc off) |
| slack-receiver | **공용** — 수신기 하나가 개발·운영 버튼을 모두 처리(Slack Request URL 이 api.readum.kr 하나) |

운영 ERROR 알림은 운영 전용 Slack 채널로 간다 — `prod.env` 의 `SLACK_WEBHOOK_URL` 이 그 채널 Incoming Webhook 을 가리키고, 알림 라벨 `env` 는 활성 프로파일 `prod` 를 그대로 쓴다(logback). 별도 Slack 앱·봇 토큰·signing secret 은 필요 없다.

### 최초 활성화가 수동인 이유

`deploy.sh` 는 운영 nginx 설정(`prod-app.conf`)을 `sites-available` 에만 두고 **활성화(cutover)는 자동으로 하지 않는다** — 개발의 최초 전환과 같은 방식이다. 활성화하려면 두 전제가 먼저 갖춰져야 하고, 그 전에 `sites-enabled` 로 심링크하면 `nginx -t` 가 실패하기 때문이다.

1. `prod-api.readum.kr` DNS A 레코드가 이 서버 EIP 로 해석될 것
2. 그 도메인용 Let's Encrypt 인증서가 발급돼 있을 것 (`ssl_certificate` 경로) — DNS 가 이 서버로 해석돼야 ACME http-01 검증이 통과하므로 **반드시 DNS 뒤에 발급**한다

두 전제가 갖춰지고 첫 배포로 `readum-prod-upstream.conf` 가 생기면, `sites-enabled` 심링크 + `nginx -t && reload` 로 운영 트래픽을 흘린다. (구체적 최초 세팅 절차는 이 구성을 도입한 PR 참조 — 한 번 하고 끝나는 작업이라 문서에 상주시키지 않는다.)

## DB (Docker MySQL)

| 항목 | 값 |
|------|----|
| 컨테이너 | readum-mysql (MySQL 8.4 — 이관 전 RDS 와 동일 버전) |
| 바인딩 | 127.0.0.1:3306 (외부 접속 불가) |
| 데이터 | `/opt/readum/mysql-data` (EBS, 컨테이너 재생성에도 유지) |
| 관측 설정 | slow_query_log ON · `log_output=TABLE`(`mysql.slow_log`) · performance_schema ON — RDS 파라미터 그룹에서 이식 |
| 백업 | `backup-mysql.sh` 일일 04:30 cron, `--single-transaction`(무중단), `/opt/readum/mysql-backup/` 로컬 7일 보관. 시점 복구 없음(최대 24시간 유실 감수) — 외부 보관(S3)은 운영 프로세스 분리 시점에 재검토 |

스키마 정본은 Flyway 마이그레이션(`src/main/resources/db/migration/`)이다. 앱 기동 시 Flyway 가 마이그레이션을 적용하고, Hibernate 가 `ddl-auto: validate` 로 엔티티와 대조한다. 엔티티를 바꾸면 반드시 같은 PR 에 마이그레이션 파일(V{n}__...)을 추가한다. 테스트(H2)는 Flyway 를 끄고 `create-drop` 을 유지한다.

## Redis

컨테이너 readum-redis (Redis 7.4), 127.0.0.1:6379 바인딩 + 비밀번호, `maxmemory 64mb`·`noeviction`. 앱이 사용 중: 사용자 토큰 예산 + OpenAI 전역 게이트(분당 예산 + quota 쿨다운). 구 circuit breaker 는 quota 쿨다운으로 게이트에 통합·Redis 공유되어 #88 의 상태 공유 항목이 해소됐다.

## 도메인 / HTTPS

| 항목 | 값 |
|------|----|
| DNS 관리 | 가비아 |
| 프론트엔드 | readum.kr (CloudFront) |
| 백엔드 API | api.readum.kr → EC2 |
| SSL 인증서 | Let's Encrypt (Certbot 자동 갱신, nginx 에서 TLS 종료). 이 서버 인증서는 **api.readum.kr 단독** — readum.kr 은 CloudFront 로 가므로 이 서버에서 ACME 검증이 안 돼 인증서에 포함하면 갱신이 실패한다 (2026-07-07 readum.kr 제거) |

서버 공인 IP 는 탄력적 IP 로 고정한다 (미고정 상태에서 인스턴스 stop/start 시 IP 가 바뀌어 DNS 가 끊긴 사고가 2026-07-06 실제로 발생). 배포 대상 주소의 원본은 `deploy.yml` 의 `env.DEPLOY_HOST`.

## 접속 방법

```bash
# EC2 SSH
ssh -i {키페어.pem} ubuntu@{EC2_PUBLIC_IP}

# MySQL — 127.0.0.1 바인딩이라 SSH 터널 경유만 가능 (DataGrip 은 SSH/SSL 탭에서 터널 설정)
ssh -i {키페어.pem} -L 3306:127.0.0.1:3306 ubuntu@{EC2_PUBLIC_IP}
mysql -h 127.0.0.1 -P 3306 -u {DB_USER} -p
```

애플리케이션 실행에 필요한 환경 변수 목록은 `src/main/resources/application*.yml` 의 `${...}` 플레이스홀더가 원본이다.

## 비용

- 운영은 당분간 이 개발 서버와 **같은 t3.small 에 공존**한다 (사용자 0명 · 환경 세팅 목적). 트래픽이 실재하면 t3.medium 증설 또는 운영 별도 인스턴스로 분리 — 그때 운영 JVM 힙(`-Xmx`)과 운영 MySQL 버퍼 풀도 상향한다.
- RDS 폐기로 DB 비용 소멸 — 그 몫을 인스턴스 증설(t3.micro → t3.small)에 사용
