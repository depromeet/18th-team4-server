# 인프라 구성

> 이 문서는 코드로 확인할 수 없는 콘솔 설정 세부(보안 그룹 규칙, 버킷 목록 등)는 담지 않는다.
> 그런 값은 문서가 금방 낡아 거짓이 되므로 AWS 콘솔을 원본으로 본다 (2026-07-05 결정).
> 인프라 파일(nginx·systemd·배포 스크립트·컨테이너 정의)의 원본은 repo 의 [`infra/`](../../infra) 디렉토리다.
> 서버에 직접 적용된 상태가 repo 와 다르면 repo 를 고쳐서 반영하는 것이 원칙이다.

## 구성도

2026-07-06 부터 단일 EC2 에 전부 상주한다 (RDS 는 Docker MySQL 로 이관 후 폐기 — 최종 스냅샷 보존).

```text
                Internet
                    │
      ┌─────────────┴─────────────┐
      │                           │
┌─────▼──────┐             ┌──────▼──────┐
│ readum.kr  │             │api.readum.kr│
│ 프론트엔드    │             │  백엔드 API  │
│ (CloudFront)│            │  HTTPS :443 │
└────────────┘             └──────┬──────┘
                                  │
                     ┌────────────▼────────────────┐
                     │  EC2 t3.small · Ubuntu       │
                     │  ap-northeast-2 · 스왑 2GB    │
                     │                              │
                     │  nginx (:80→:443, TLS 종료)   │
                     │    │ upstream readum_backend │
                     │    ▼                         │
                     │  readum-blue(:8081) ─┐       │
                     │  readum-green(:8082)─┤ 한쪽만 │
                     │   (systemd 유닛 2개)  │ 상주   │
                     │                              │
                     │  Docker:                     │
                     │   readum-mysql (MySQL 8.4)   │
                     │   readum-redis (Redis 7.4)   │
                     │   → 둘 다 127.0.0.1 바인딩     │
                     └──────────────────────────────┘
```

## 배포 (blue/green)

같은 앱을 blue(:8081)/green(:8082) 두 systemd 유닛으로 번갈아 띄우고, nginx 의 upstream 파일 하나를 갈아끼워 트래픽을 전환한다. 다운타임은 nginx reload 수준(수십 ms)이다.

빌드와 배포 실행은 GitHub Actions([`deploy.yml`](../../.github/workflows/deploy.yml))가 수행한다 — CI 흐름 자체는 그 파일이 원본이고, 여기는 서버 쪽 구조만 적는다.

| 구성물 | 위치 (서버) | 원본 |
|---|---|---|
| nginx server 블록 | `/etc/nginx/sites-available/app.conf` | `infra/nginx/app.conf` |
| 전환 스위치(upstream) | `/etc/nginx/conf.d/readum-upstream.conf` | 없음 — "지금 어느 색이 활성인가"라는 런타임 상태. `deploy.sh` 가 생성·갱신하며 활성 색 판정도 이 파일에서 읽는다 |
| systemd 유닛 | `/etc/systemd/system/readum-{blue,green}.service` | `infra/systemd/` |
| 배포 스크립트 | `/opt/readum/bin/deploy.sh` | `infra/scripts/deploy.sh` (CI 가 배포 때마다 동기화) |
| jar | `/opt/readum/{blue,green}/app.jar`, 보관 `/opt/readum/releases/` (최근 5개) | CI 가 빌드·업로드 |
| 환경 변수 | `/opt/readum/.env` | GitHub Secret `ENV_FILE` — CI 가 배포 때마다 재작성. 변경 = Secret 수정 → 재배포. **`SERVER_PORT` 금지**(유닛의 포트 지정을 덮어씀) |
| MySQL 설정 | `/opt/readum/mysql/my.cnf` | `infra/mysql/my.cnf` (반영은 `setup.sh` + 컨테이너 재시작) |
| 컨테이너 정의 | — | `infra/docker/docker-compose.dev.yml` (로컬 개발용은 `docker-compose.local.yml`) |
| sudo 권한 | `/etc/sudoers.d/readum-deploy` | `infra/sudoers/readum-deploy` |

서버에서 직접 쓰는 명령:

```bash
/opt/readum/bin/deploy.sh status     # 활성 색·양쪽 유닛 상태·readiness
/opt/readum/bin/deploy.sh rollback   # 직전 버전(반대 색 jar)으로 되돌리기 (~1분)
```

배포 수칙:

- ~~**05:50~06:10 배포 회피**~~ (2026-07 해소) — 6시 감상문 적재 스캔과 전환 구간(두 프로세스 동시 상주)이 겹치면 외부 API(OpenAI) 호출 예산이 잠깐 2배가 됐었다. 전역 게이트(Redis) 도입으로 호출 예산이 프로세스 간 공유되어 이 회피는 불필요해졌다.
- 전환 구간엔 JVM 2개(각 `-Xmx256m`)가 동시에 뜬다. 배포 중 스왑 피크가 계속 커지면 인스턴스 증설을 검토한다.
- nginx·systemd·sudoers 파일 변경은 CI 가 반영하지 않는다(root 권한 불필요 원칙) — `sudo ./infra/scripts/setup.sh <repo>/infra` 재실행 + 필요 시 nginx reload 로 수동 반영한다.

새 서버를 처음 준비할 때도 같은 `setup.sh` 가 시작점이다 (디렉토리·유닛·sudoers·MySQL 설정 배치).

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

컨테이너 readum-redis (Redis 7.4), 127.0.0.1:6379 바인딩 + 비밀번호, `maxmemory 64mb`·`noeviction`. 앱이 사용 중: 사용자 토큰 예산 + OpenAI 전역 게이트 (circuit breaker 상태 공유는 #88 잔여).

## 도메인 / HTTPS

| 항목 | 값 |
|------|----|
| DNS 관리 | 가비아 |
| 프론트엔드 | readum.kr (CloudFront) |
| 백엔드 API | api.readum.kr → EC2 |
| SSL 인증서 | Let's Encrypt (Certbot 자동 갱신, nginx 에서 TLS 종료) |

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

- 운영 서버는 별도 프로비저닝 예정 (그 전까지 이 개발 서버가 운영을 겸함)
- RDS 폐기로 DB 비용 소멸 — 그 몫을 인스턴스 증설(t3.micro → t3.small)에 사용
