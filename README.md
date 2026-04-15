# readwith

## 로컬 개발 환경 세팅

### 1. 사전 요구사항

| 도구 | 버전 | 설치 링크 |
|------|------|----------|
| Docker Desktop | 최신 버전 | [다운로드](https://www.docker.com/products/docker-desktop/) |
| Java | 25 | [다운로드](https://jdk.java.net/25/) |

> Gradle Toolchain이 설정되어 있어, 프로젝트 빌드 시 Java 25가 없으면 자동으로 다운로드를 시도합니다.

---

### 2. .env 설정

프로젝트 루트에서 `.env.example`을 복사해 `.env`를 생성합니다.

```bash
cp .env.example .env
```

`.env` 파일을 열어 각 값을 로컬 환경에 맞게 수정합니다.

```dotenv
MYSQL_ROOT_PASSWORD=your_root_password   # MySQL root 계정 비밀번호
MYSQL_DATABASE=readwith                  # 사용할 DB 이름
MYSQL_USER=readwith                      # 애플리케이션 DB 유저
MYSQL_PASSWORD=your_password             # 애플리케이션 DB 비밀번호
MYSQL_PORT=3306                          # 호스트에 노출할 포트 (기본 3306, 충돌 시 변경)
```

> `.env`는 `.gitignore`에 등록되어 있어 저장소에 커밋되지 않습니다.

---

### 3. MySQL 컨테이너 구동

```bash
docker-compose up -d
```

- MySQL 8.0 컨테이너가 백그라운드로 실행됩니다.
- 데이터는 Docker named volume(`mysql_data`)에 저장되어 컨테이너를 내려도 유지됩니다.

**상태 확인:**

```bash
docker-compose ps        # 컨테이너 실행 상태 확인
docker-compose logs mysql  # MySQL 로그 확인
```

**컨테이너 종료:**

```bash
docker-compose down      # 컨테이너 중지 (데이터 유지)
docker-compose down -v   # 컨테이너 + 볼륨 삭제 (데이터 초기화)
```

---

### 4. IntelliJ Run Configuration 설정

IntelliJ에서 Spring Boot를 `local` 프로파일로 실행하려면 Run Configuration을 아래와 같이 설정합니다.

1. 상단 메뉴 **Run → Edit Configurations** 선택
2. `ReadwithApplication` 실행 구성 선택 (없으면 `+` → `Spring Boot` 추가)
3. 아래 항목을 설정합니다.

**Active profiles**

```
local
```

**Environment variables** — `.env`의 값을 그대로 입력합니다.

```
MYSQL_ROOT_PASSWORD=your_root_password;MYSQL_DATABASE=readwith;MYSQL_USER=readwith;MYSQL_PASSWORD=your_password;MYSQL_PORT=3306
```

> 세미콜론(`;`)으로 구분해 한 줄에 입력합니다.  
> 또는 우측 `...` 버튼을 눌러 항목별로 개별 입력할 수 있습니다.

4. **OK** 저장 후 실행합니다.

---

### 5. Spring Boot 실행

**IntelliJ:** 4번 설정 후 `Run` 버튼 클릭

**터미널:**

```bash
# .env의 환경 변수를 현재 셸에 로드한 뒤 실행
export $(grep -v '^#' .env | xargs) && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

실행 후 `application-local.yml`이 적용되어 로컬 MySQL에 접속합니다.

---

## 환경 변수 목록 (`.env`)

| 변수명 | 설명 | 기본값 예시 |
|--------|------|------------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호 | `rootpassword` |
| `MYSQL_DATABASE` | 사용할 데이터베이스 이름 | `readwith` |
| `MYSQL_USER` | 애플리케이션 DB 유저 | `readwith` |
| `MYSQL_PASSWORD` | 애플리케이션 DB 비밀번호 | `readwith1234` |
| `MYSQL_PORT` | 호스트에서 노출할 포트 | `3306` |

---

## 빌드 & 테스트

```bash
./gradlew build
./gradlew test
./gradlew clean build
```
