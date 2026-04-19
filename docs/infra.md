# 인프라 구성

## 구성도

### 개발 서버 (AWS)

```
                    Internet
                        │
          ┌─────────────┴─────────────┐
          │                           │
    ┌─────▼──────┐             ┌──────▼──────┐
    │ readum.kr  │             │api.readum.kr│
    │ 프론트엔드    │             │  백엔드 API  │
    │ HTTPS :443 │             │  HTTPS :443 │
    └─────┬──────┘             └──────┬──────┘
          │                           │
          └─────────┬─────────────────┘
                    │
           ┌────────▼────────┐
           │   EC2 Instance  │
           │  t3.micro       │
           │  Ubuntu         │
           │  ap-northeast-2 │
           │                 │
           │  Nginx          │
           │  :80 → :443     │
           └───┬─────────┬───┘
               │         │
    ┌──────────▼───┐  ┌───▼──────────────────┐
    │     RDS      │  │          S3          │
    │  MySQL 8.4   │  │  readum-{account-id} │
    │  db.t4g.micro│  │  -ap-northeast-2-an  │
    │  단일 AZ      │  │  이미지/파일 저장        │
    │  :3306       │  │                      │
    └──────────────┘  └──────────────────────┘
```

---

## 서비스 상세

### EC2

| 항목 | 값 |
|------|----|
| 인스턴스 타입 | t3.micro |
| OS | Ubuntu |
| 리전 | ap-northeast-2 |
| 역할 | Nginx 리버스 프록시 |

- Certbot으로 Let's Encrypt SSL 인증서 발급 및 자동 갱신

### RDS

| 항목 | 값 |
|------|----|
| 인스턴스 ID | readum-dev |
| 엔진 | MySQL 8.4.8 |
| 인스턴스 타입 | db.t4g.micro |
| 포트 | 3306 |
| DB 이름 | readum |
| 가용 영역 | 단일 AZ |
| 스토리지 | 20GiB (gp2, 자동 확장 최대 1000GiB) |
| 암호화 | 활성화 |
| SSL | 필수 |
| 퍼블릭 액세스 | 활성화 (개발 편의용) |

### S3

| 항목 | 값 |
|------|----|
| 버킷명 | readum-{account-id}-ap-northeast-2-an |
| 리전 | ap-northeast-2 |
| 역할 | 이미지 및 파일 업로드 스토리지 |

### 도메인 / HTTPS

| 항목 | 값 |
|------|----|
| DNS 관리 | 가비아 |
| 프론트엔드 도메인 | readum.kr |
| 백엔드 API 도메인 | api.readum.kr |
| SSL 인증서 | Let's Encrypt (Certbot) |
| 갱신 | Certbot 자동 갱신 |

---

## 보안 그룹

### EC2 보안 그룹 (readwith-dev-sg)

| 포트 | 프로토콜 | 소스 | 용도 |
|------|---------|------|------|
| 22 | TCP | 개발자 IP/32 | SSH |
| 80 | TCP | 0.0.0.0/0 | HTTP |
| 443 | TCP | 0.0.0.0/0 | HTTPS |

### RDS 보안 그룹 (rds-ec2-2)

| 포트 | 프로토콜 | 소스 | 용도 |
|------|---------|------|------|
| 3306 | TCP | EC2 보안 그룹 | EC2 → RDS 연결 |
| 3306 | TCP | 개발자 로컬 IP | 로컬 개발 접속 |

> ⚠️ 로컬에서 RDS 접속 시 본인 IP를 RDS 보안 그룹 인바운드 규칙에 추가해야 합니다.

---

## 접속 방법

### EC2 SSH 접속

```bash
ssh -i {키페어.pem} ubuntu@{EC2_PUBLIC_IP}
```

### RDS 로컬 직접 접속 (SSL)

RDS CA 인증서 다운로드 (최초 1회):

```bash
curl -o ~/global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
```

MySQL CLI 접속:

```bash
mysql -h {RDS_ENDPOINT} \
      -P 3306 -u {DB_USER} -p \
      --ssl-mode=VERIFY_IDENTITY \
      --ssl-ca=~/global-bundle.pem
```

### 로컬 환경 변수 설정 (IntelliJ Run Configuration)

```
SPRING_PROFILES_ACTIVE=dev
MYSQL_HOST={RDS_ENDPOINT}
MYSQL_PORT=3306
MYSQL_DATABASE=readum
MYSQL_USER={DB_USER}
MYSQL_PASSWORD={비밀번호}
```

---

## 비용 최적화

- 운영 서버는 런칭 직전 별도 프로비저닝 예정
- RDS 다중 AZ 미사용
- RDS 성능 개선 도우미 비활성화
