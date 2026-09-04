package com.readum.infrastructure.ai.openai.chat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRateLimitGuard;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AiChatClientImplTest {

    @Mock
    private ChatClient chatClient;

    // [측정용 임시 — 조건 A] 스트리밍 전용 ChatClient. 이 테스트가 다루는 게이트 보상 경로에서는 쓰이지 않는다.
    @Mock
    private ChatClient streamingChatClient;

    @Mock
    private AiPromptAuditLogger auditLogger;

    @Mock
    private OpenAiRateLimitGuard rateLimitGuard;

    private final TokenCounter tokenCounter = text -> 0;

    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.Context(8000, 2000, 4000, 800),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5),
            new AiChatProperties.TokenBudget(120000, 512)
    );

    private AiChatClientImpl aiChatClient;

    @BeforeEach
    void setUp() {
        aiChatClient = new AiChatClientImpl(
                chatClient, streamingChatClient, auditLogger, rateLimitGuard, aiChatProperties, tokenCounter);
    }

    // 확보 쪽 번역(가드의 Optional → Counted/Uncounted)은 여기서 직접 단언하지 않는다 — 의도된 공백이다.
    // acquireRateLimitPermit 은 @Value 로 주입되는 모델 이름·시스템 프롬프트 파일에 기대므로
    // 그 두 필드를 채우지 않고는 단위 수준으로 구성할 수 없다 (리플렉션으로 주입하지는 않는다).
    // 확보 쪽은 OpenAiRateLimitGuardTest(Optional 반환)와 AiChatStreamGuardrailTest(통합)가 받친다.

    @Test
    void 계상된_permit_의_release_는_확보_시점의_분_키_내역으로_게이트_보상_차감을_호출한다() {
        aiChatClient.releaseRateLimitPermit(
                new AiChatClient.RateLimitPermit.Counted("gpt-4o-mini", 29_000_000L, 4500));

        verify(rateLimitGuard).compensate(
                new OpenAiRequestGate.GateReservation("gpt-4o-mini", 29_000_000L, 4500));
    }

    @Test
    void 계상_없는_permit_의_release_는_게이트에_접근하지_않는다() {
        aiChatClient.releaseRateLimitPermit(new AiChatClient.RateLimitPermit.Uncounted());

        verifyNoInteractions(rateLimitGuard);
    }
}
