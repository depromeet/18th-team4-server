# readwith

## 로컬 개발 환경 세팅

### 사전 요구사항

| 도구 | 버전 |
|------|------|
| Java | 25 |
| MySQL | 8.x |

---

### 환경 변수

| 변수명 | 설명 |
|--------|------|
| `OPENAI_API_KEY` | OpenAI API 키 |
| `MYSQL_HOST` | MySQL 호스트 |
| `MYSQL_PORT` | MySQL 포트 (기본값: `3306`) |
| `MYSQL_DATABASE` | 데이터베이스 이름 |
| `MYSQL_USER` | DB 유저 |
| `MYSQL_PASSWORD` | DB 비밀번호 |

---

### 실행

**Active profiles:** `dev`

IntelliJ Run Configuration에서 환경 변수를 설정합니다.

```dotenv
OPENAI_API_KEY=...;MYSQL_HOST=...;MYSQL_PORT=3306;MYSQL_DATABASE=...;MYSQL_USER=...;MYSQL_PASSWORD=...
```

---

## 빌드 & 테스트

```bash
./gradlew build
./gradlew test
./gradlew clean build
```
