# 설정값 (Configuration Properties) 규칙

> 적용 대상: 비즈니스 룰/외부 API 설정값을 `@ConfigurationProperties` record 로 외부화할 때, 그 파일을 어느 패키지에 둘지 정할 때.

| 종류 | 위치 | 예시 |
|------|------|------|
| **외부 API/시스템 설정** (시크릿, 호스트, 타임아웃) | `infrastructure/{feature}/{provider}/{Provider}Properties.java` | `infrastructure/book/aladin/AladinProperties` |
| **도메인 비즈니스 룰** (검증 한계, 컨텍스트 크기 등) | `domain/{feature}/config/{Domain}Properties.java` | `domain/aiChat/config/AiChatProperties` |

도메인 비즈니스 룰은 nested record 로 그룹핑한다.

```java
@ConfigurationProperties(prefix = "ai-chat")
public record AiChatProperties(
        ContextWindow contextWindow,
        MessageRule message
) {
    public record ContextWindow(int maxTurns) {
        public int maxMessages() { return maxTurns * 2; }
    }
    public record MessageRule(int maxContentLength) {}
}
```

```yaml
# application.yml
ai-chat:
  context-window:
    max-turns: 20
  message:
    max-content-length: 1000
```

- 등록은 `ReadumApplication` 의 `@ConfigurationPropertiesScan` 이 자동 처리 (별도 `@EnableConfigurationProperties` 불필요)
- 시크릿/환경별 값은 yml 에 직접 박지 말고 환경변수 (`${OPENAI_API_KEY}`)
- 비즈니스 룰은 환경 무관 상수이므로 `application.yml` 에 직접값 (재배포 가능)
