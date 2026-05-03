package com.readum.infrastructure.ai.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "readum.guardrail")
public record GuardrailProperties(
        Input input,
        Output output,
        Moderation moderation,
        RateLimit rateLimit
) {

    public GuardrailProperties {
        if (input == null) input = Input.defaults();
        if (output == null) output = Output.defaults();
        if (moderation == null) moderation = Moderation.defaults();
        if (rateLimit == null) rateLimit = RateLimit.defaults();
    }

    public record Input(
            int maxCharacters,
            int maxTokens,
            List<String> sensitiveWords,
            List<String> injectionPatterns,
            String failureResponse
    ) {
        public static Input defaults() {
            return new Input(
                    4000,
                    1500,
                    List.of(),
                    List.of(
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
                    ),
                    "요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요."
            );
        }
    }

    public record Output(
            int maxResponseTokens,
            String failureResponse
    ) {
        public static Output defaults() {
            return new Output(
                    1024,
                    "응답을 안전하게 생성할 수 없어 거부되었습니다. 다른 질문을 해주세요."
            );
        }
    }

    public record Moderation(
            boolean enabled,
            String model
    ) {
        public static Moderation defaults() {
            return new Moderation(true, "omni-moderation-latest");
        }
    }

    public record RateLimit(
            boolean enabled,
            int requestsPerMinute,
            int dailyRequests
    ) {
        public static RateLimit defaults() {
            return new RateLimit(true, 20, 200);
        }
    }
}
