# 인프라 구성

> 이 문서는 코드로 확인할 수 없는 콘솔 설정 세부(보안 그룹 규칙, 버킷 목록 등)는 담지 않는다.
> 그런 값은 문서가 금방 낡아 거짓이 되므로 AWS 콘솔을 원본으로 본다 (2026-07-05 결정).

## 구성도

### 개발 서버 (AWS)

```text
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
           └────────┬────────┘
                    │
           ┌────────▼─────┐
           │     RDS      │
           │  MySQL 8.4   │
           │  db.t4g.micro│
           │  단일 AZ      │
           │  :3306       │
           └──────────────┘
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

### 도메인 / HTTPS

| 항목 | 값 |
|------|----|
| DNS 관리 | 가비아 |
| 프론트엔드 도메인 | readum.kr |
| 백엔드 API 도메인 | api.readum.kr |
| SSL 인증서 | Let's Encrypt (Certbot) |
| 갱신 | Certbot 자동 갱신 |

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

> 접속이 거부되면 RDS 보안 그룹 인바운드에 본인 IP 를 추가해야 한다 (AWS 콘솔에서 직접).

애플리케이션 실행에 필요한 환경 변수는 `src/main/resources/application*.yml` 의 `${...}` 플레이스홀더가 원본이다.

---

## 비용 최적화

- 운영 서버는 런칭 직전 별도 프로비저닝 예정
- RDS 다중 AZ 미사용
- RDS 성능 개선 도우미 비활성화
