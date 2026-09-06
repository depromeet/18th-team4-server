# readum

AI 와 대화하며 독서 감상을 기록하는 서비스의 백엔드 서버. Spring Boot 로 구현했다.

## 빠른 시작

```bash
./gradlew bootRun
```

실행 전 필요한 환경 변수(OpenAI 키·DB 접속 정보)는
`src/main/resources/application*.yml` 의 `${...}` 플레이스홀더를 참조한다.

## 문서

문서 지도는 [docs/README.md](docs/README.md) 에서 시작한다.

AI 작업자(Claude Code 등)는 [CLAUDE.md](CLAUDE.md) 의 지침을 따른다.
