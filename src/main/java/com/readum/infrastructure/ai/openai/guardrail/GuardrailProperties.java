package com.readum.infrastructure.ai.openai.guardrail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "readum.guardrail")
public record GuardrailProperties(
        @Valid @NotNull Input input,
        @Valid @NotNull Output output,
        @Valid @NotNull Moderation moderation
) {

    public GuardrailProperties {
        if (input == null) input = Input.defaults();
        if (output == null) output = Output.defaults();
        if (moderation == null) moderation = Moderation.defaults();
    }

    public record Input(
            @Min(1) int maxCharacters,
            @Min(1) int maxTokens,
            @NotNull List<String> sensitiveWords,
            @NotEmpty List<String> injectionPatterns,
            @NotBlank String failureResponse
    ) {
        private static final int DEFAULT_MAX_CHARACTERS = 4000;
        private static final int DEFAULT_MAX_TOKENS = 1500;
        private static final List<String> DEFAULT_SENSITIVE_WORDS = List.of();
        private static final List<String> DEFAULT_INJECTION_PATTERNS = List.of(
                "(?i)ignore (all |any |the )?(previous|prior|above) (instructions|prompts?|messages?)",
                "(?i)disregard (all |any |the )?(previous|prior|above) (instructions|prompts?|messages?)",
                "(?i)forget (all |any |the )?(previous|prior|above) (instructions|prompts?|messages?)",
                "(?i)###\\s*system\\s*###",
                "(?i)<\\s*/\\s*system\\s*>",
                "(?i)you are now (an? )?(unrestricted|developer|admin|jailbroken)",
                "(?i)\\bDAN\\b.{0,80}(do anything now|jailbreak|no restrictions)",
                "(?i)(?:이제부터|지금부터)\\s*너는\\s*DAN\\b",
                "이전\\s*(모든\\s*)?지시(사항)?\\s*(을|를)?\\s*(무시|잊어)",
                "지금부터\\s*너는\\s*(?:readum-)?(admin|관리자|개발자|시스템)",
                "(?i)system\\s*prompt\\s*(을|를)?\\s*(출력|보여|알려|dump)",
                "(?i)repeat the (words|text|instructions) above"
        );
        private static final String DEFAULT_FAILURE_RESPONSE =
                "요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요.";

        public Input {
            // 부분 바인딩 시(YAML 에 input 블록은 있지만 일부 필드가 누락된 경우) defaults 로 보완.
            // injectionPatterns 가 비어 있으면 PromptInjectionPatternAdvisor 가 침묵 미등록되므로
            // 반드시 기본 패턴 셋으로 보강해야 한다.
            if (maxCharacters <= 0) maxCharacters = DEFAULT_MAX_CHARACTERS;
            if (maxTokens <= 0) maxTokens = DEFAULT_MAX_TOKENS;
            if (sensitiveWords == null) sensitiveWords = DEFAULT_SENSITIVE_WORDS;
            if (injectionPatterns == null || injectionPatterns.isEmpty()) {
                injectionPatterns = DEFAULT_INJECTION_PATTERNS;
            }
            if (failureResponse == null || failureResponse.isBlank()) {
                failureResponse = DEFAULT_FAILURE_RESPONSE;
            }
        }

        public static Input defaults() {
            return new Input(
                    DEFAULT_MAX_CHARACTERS,
                    DEFAULT_MAX_TOKENS,
                    DEFAULT_SENSITIVE_WORDS,
                    DEFAULT_INJECTION_PATTERNS,
                    DEFAULT_FAILURE_RESPONSE
            );
        }
    }

    public record Output(
            @Min(1) int maxResponseTokens,
            @NotBlank String failureResponse
    ) {
        private static final int DEFAULT_MAX_RESPONSE_TOKENS = 1024;
        private static final String DEFAULT_FAILURE_RESPONSE =
                "응답을 안전하게 생성할 수 없어 거부되었습니다. 다른 질문을 해주세요.";

        public Output {
            if (maxResponseTokens <= 0) maxResponseTokens = DEFAULT_MAX_RESPONSE_TOKENS;
            if (failureResponse == null || failureResponse.isBlank()) {
                failureResponse = DEFAULT_FAILURE_RESPONSE;
            }
        }

        public static Output defaults() {
            return new Output(DEFAULT_MAX_RESPONSE_TOKENS, DEFAULT_FAILURE_RESPONSE);
        }
    }

    public record Moderation(
            @NotBlank String model,
            @NotNull List<String> alwaysBlockCategories,
            @NotNull List<String> bookContextRelaxedCategories,
            @NotNull FailurePolicy failurePolicy
    ) {
        private static final String DEFAULT_MODEL = "omni-moderation-latest";

        // 책 맥락 유무와 무관하게 항상 차단하는 카테고리.
        // 주의: Spring AI 2.0.0-M4 의 org.springframework.ai.moderation.Categories 에는
        // OpenAI 의 illicit / illicit-violent getter 가 없다. 의미상 가장 가까운
        // dangerous-and-criminal-content(isDangerousAndCriminalContent) 로 대체한다.
        // 카테고리 이름↔getter 매핑의 정본은 OpenAiInputModerationClientImpl 의 레지스트리이며,
        // 여기 적힌 이름이 그 레지스트리에 없으면 부팅이 fail-fast 한다(조용한 무시 방지).
        private static final List<String> DEFAULT_ALWAYS_BLOCK_CATEGORIES = List.of(
                "self-harm",
                "self-harm-intent",
                "self-harm-instructions",
                "sexual-minors",
                "dangerous-and-criminal-content"
        );
        // 책 맥락(독서 토론) 안에서는 정상 질문으로 흔히 등장하므로, bookContext 가 있을 때만 통과시키는 카테고리.
        private static final List<String> DEFAULT_BOOK_CONTEXT_RELAXED_CATEGORIES = List.of(
                "violence",
                "violence-graphic",
                "harassment",
                "harassment-threatening",
                "sexual",
                "hate",
                "hate-threatening"
        );

        public Moderation {
            if (model == null || model.isBlank()) model = DEFAULT_MODEL;
            if (alwaysBlockCategories == null) alwaysBlockCategories = DEFAULT_ALWAYS_BLOCK_CATEGORIES;
            if (bookContextRelaxedCategories == null) {
                bookContextRelaxedCategories = DEFAULT_BOOK_CONTEXT_RELAXED_CATEGORIES;
            }
            if (failurePolicy == null) failurePolicy = FailurePolicy.CLOSED;
        }

        public static Moderation defaults() {
            return new Moderation(
                    DEFAULT_MODEL,
                    DEFAULT_ALWAYS_BLOCK_CATEGORIES,
                    DEFAULT_BOOK_CONTEXT_RELAXED_CATEGORIES,
                    FailurePolicy.CLOSED
            );
        }

        /**
         * 외부 Moderation API 장애 시의 기본 동작.
         * CLOSED(기본): 차단 우선 — UNAVAILABLE 로 처리해 HTTP 503 으로 응답(운영 안전).
         * OPEN: 통과 우선 — WARN 로그 후 정상 흐름 진행(개발 환경 전용).
         */
        public enum FailurePolicy {
            OPEN, CLOSED
        }
    }
}
