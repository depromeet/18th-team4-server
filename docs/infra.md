# 인프라 구성

## 구성도

### 개발 서버 (AWS)

```
              Internet
                  │
          ┌───────▼───────┐
          │   readum.kr   │
          │  Let's Encrypt│
          │    HTTPS :443 │
          └───────┬───────┘
                  │
         ┌────────▼────────┐
         │   EC2 Instance  │
         │  t3.micro       │
         │  Ubuntu 22.04   │
         │  ap-northeast-2c│
         │                 │
         │  Nginx          │
         │  :80 → :443     │
         │  :443 → :8080   │
         │                 │
         │  Spring Boot    │
         │  :8080          │
         └───┬─────────┬───┘
             │         │
  ┌──────────▼──┐  ┌───▼──────────┐
  │     RDS     │  │      S3      │
  │  MySQL 8.0  │  │  readum 버킷 │
  │  db.t3.micro│  │              │
  │  단일 AZ    │  │              │
  │  :3306      │  │              │
  └─────────────┘  └──────────────┘
```

---

## 서비스 상세

### EC2

| 항목 | 값 |
|------|----|
| 인스턴스 타입 | t3.micro |
| OS | Ubuntu 22.04 LTS |
| 리전 / 가용 영역 | ap-northeast-2 / ap-northeast-2c |
| 역할 | Spring Boot 애플리케이션 실행, Nginx 리버스 프록시 |

- Docker 미사용 — Spring Boot 앱을 EC2에서 직접 실행 (프리티어 리소스 절약)
- Nginx가 443 요청을 8080으로 프록시
- Certbot으로 Let's Encrypt SSL 인증서 발급 및 자동 갱신

### RDS

| 항목 | 값 |
|------|----|
| 엔진 | MySQL 8.0 |
| 인스턴스 타입 | db.t3.micro |
| 가용 영역 | 단일 AZ |
| 포트 | 3306 |
| 역할 | 운영 데이터베이스 |

- EC2 보안 그룹에서만 3306 인바운드 허용 (퍼블릭 접근 차단)
- 로컬 개발 시 RDS 직접 연결 대신 docker-compose 로컬 MySQL 사용

### S3

| 항목 | 값 |
|------|----|
| 버킷명 | readum |
| 리전 | ap-northeast-2 |
| 역할 | 정적 파일 및 사용자 업로드 스토리지 |

### 도메인 / HTTPS

| 항목 | 값 |
|------|----|
| 도메인 | readum.kr |
| SSL 인증서 | Let's Encrypt (Certbot) |
| 갱신 | Certbot 자동 갱신 (cron) |

---

## 로컬 / 개발 서버 환경 비교

| 항목 | 로컬 | 개발 서버 |
|------|------|----------|
| Spring 프로파일 | `local` | `prod` |
| DB | Docker Compose MySQL 8.0 | RDS MySQL 8.0 |
| DB 접속 | `localhost:${MYSQL_PORT}` | RDS 엔드포인트 |
| HTTPS | 미적용 | readum.kr (Certbot) |
| 앱 실행 | `./gradlew bootRun` | EC2 직접 실행 |
| 설정 파일 | `application-local.yml` | `application-prod.yml` |

---

## 접속 방법

### EC2 SSH 접속

```bash
ssh -i {키페어.pem} ubuntu@{EC2_PUBLIC_IP}
```

### 애플리케이션 배포

```bash
# EC2 접속 후
git pull origin main
./gradlew build -x test
sudo systemctl restart readwith
```

### RDS 로컬 직접 접속 (SSH 터널링)

RDS는 퍼블릭 접근이 차단되어 있어 EC2를 통한 터널링이 필요합니다.

```bash
# 터널 열기
ssh -i {키페어.pem} -L 3307:{RDS_ENDPOINT}:3306 ubuntu@{EC2_PUBLIC_IP} -N

# 이후 localhost:3307 로 접속
mysql -h 127.0.0.1 -P 3307 -u {DB_USER} -p
```
