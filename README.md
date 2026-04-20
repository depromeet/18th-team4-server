# readum

## 로컬 개발 환경 세팅

### 1. 사전 요구사항

| 도구 | 버전 | 설치 링크 |
|------|------|----------|
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
MYSQL_DATABASE=readum                  # 사용할 DB 이름
MYSQL_USER=readum                      # 애플리케이션 DB 유저
MYSQL_PASSWORD=your_password             # 애플리케이션 DB 비밀번호
MYSQL_PORT=3306                          # 접속 포트 (기본 3306)
JWT_SECRET=your_base64_jwt_secret        # Base64 인코딩된 256bit 이상 시크릿 (예: `openssl rand -base64 48`)
```

> `JWT_SECRET`이 비어 있으면 애플리케이션이 시작 단계에서 실패(fail-fast)합니다. 반드시 값을 채운 뒤 실행하세요.

> `.env`는 `.gitignore`에 등록되어 있어 저장소에 커밋되지 않습니다.

---

### 3. IntelliJ Run Configuration 설정

IntelliJ에서 Spring Boot를 `local` 프로파일로 실행하려면 Run Configuration을 아래와 같이 설정합니다.

1. 상단 메뉴 **Run → Edit Configurations** 선택
2. `ReadumApplication` 실행 구성 선택 (없으면 `+` → `Spring Boot` 추가)
3. 아래 항목을 설정합니다.

**Active profiles**

```
local
```

**Environment variables** — `.env`의 값을 그대로 입력합니다.

```
MYSQL_DATABASE=readum;MYSQL_USER=readum;MYSQL_PASSWORD=your_password;MYSQL_PORT=3306;JWT_SECRET=your_base64_jwt_secret
```

> 세미콜론(`;`)으로 구분해 한 줄에 입력합니다.  
> 또는 우측 `...` 버튼을 눌러 항목별로 개별 입력할 수 있습니다.

4. **OK** 저장 후 실행합니다.

---

### 4. Spring Boot 실행

**IntelliJ:** 4번 설정 후 `Run` 버튼 클릭

**터미널:**

```bash
# .env의 환경 변수를 현재 셸에 로드한 뒤 실행
set -a && source .env && set +a && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

실행 후 `application-local.yml`이 적용되어 로컬 MySQL에 접속합니다.

---

## 환경 변수 목록 (`.env`)

| 변수명 | 설명 | 기본값 예시 |
|--------|------|------------|
| `MYSQL_DATABASE` | 사용할 데이터베이스 이름 | `readum` |
| `MYSQL_USER` | 애플리케이션 DB 유저 | `readum` |
| `MYSQL_PASSWORD` | 애플리케이션 DB 비밀번호 | `readum1234` |
| `MYSQL_PORT` | 접속 포트 | `3306` |
| `JWT_SECRET` | JWT 서명용 Base64 시크릿 (256bit+, 누락 시 부팅 실패) | `openssl rand -base64 48` 결과값 |

---

## 빌드 & 테스트

```bash
./gradlew build
./gradlew test
./gradlew clean build
```
