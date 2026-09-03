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

    @Mock
    private AiPromptAuditLogger auditLogger;

    @Mock
    private OpenAiRateLimitGuard rateLimitGuard;

    @Mock
    private OpenAiRequestGate requestGate;

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
                chatClient, auditLogger, rateLimitGuard, requestGate, aiChatProperties, tokenCounter);
    }

    @Test
    void 계상된_permit_의_release_는_확보_시점의_분_키_내역으로_게이트_보상_차감을_호출한다() {
        aiChatClient.releaseRateLimitPermit(
                new AiChatClient.RateLimitPermit.Counted("gpt-4o-mini", 29_000_000L, 4500));

        verify(requestGate).compensate(
                new OpenAiRequestGate.GateReservation("gpt-4o-mini", 29_000_000L, 4500));
    }

    @Test
    void 계상_없는_permit_의_release_는_게이트에_접근하지_않는다() {
        aiChatClient.releaseRateLimitPermit(new AiChatClient.RateLimitPermit.Uncounted());

        verifyNoInteractions(requestGate);
    }
}
