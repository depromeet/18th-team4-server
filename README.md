# readwith

## 로컬 개발 환경 세팅

### 사전 준비

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) 설치
- Java 25 설치 (또는 Gradle toolchain이 자동 다운로드)

### 1. 환경 변수 설정

프로젝트 루트에서 `.env.example`을 복사해 `.env`를 생성한 뒤, 값을 필요에 따라 수정합니다.

```bash
cp .env.example .env
```

`.env` 파일은 `.gitignore`에 포함되어 있어 저장소에 커밋되지 않습니다.

### 2. Docker Compose로 DB 구동

```bash
docker-compose up -d
```

MySQL 8.0 컨테이너가 시작되며 데이터는 Docker named volume(`mysql_data`)에 영속적으로 저장됩니다.

컨테이너 상태 확인:

```bash
docker-compose ps
docker-compose logs mysql
```

### 3. 애플리케이션 실행

`local` 프로파일로 실행하면 `application-local.yml`의 DB 접속 정보가 적용됩니다.

```bash
# 환경 변수를 .env에서 직접 로드하여 실행
export $(grep -v '^#' .env | xargs) && \
  SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

또는 IntelliJ에서 실행할 경우, **Run Configuration → Environment variables**에 `.env` 내용을 추가하고 **Active profiles**를 `local`로 설정합니다.

### 4. 환경 종료

```bash
# 컨테이너 중지 (데이터 유지)
docker-compose down

# 컨테이너 + 볼륨 모두 삭제 (데이터 초기화)
docker-compose down -v
```

---

## 환경 변수 목록 (`.env`)

| 변수명               | 설명                  | 기본값 예시      |
|--------------------|-----------------------|-----------------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호   | `rootpassword`  |
| `MYSQL_DATABASE`   | 사용할 데이터베이스 이름  | `readwith`      |
| `MYSQL_USER`       | 애플리케이션 DB 유저     | `readwith`      |
| `MYSQL_PASSWORD`   | 애플리케이션 DB 비밀번호 | `readwith1234`  |
| `MYSQL_PORT`       | 호스트에서 노출할 포트   | `3306`          |

---

## 빌드 & 테스트

```bash
./gradlew build
./gradlew test
./gradlew clean build
```
