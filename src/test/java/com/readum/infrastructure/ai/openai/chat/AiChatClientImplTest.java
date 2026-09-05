package com.readum.infrastructure.ai.openai.chat;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AiChatClientImplTest {

    @Mock
    private ChatClient chatClient;

    // 스트리밍 전용 ChatClient. 이 테스트가 다루는 게이트 보상 경로에서는 쓰이지 않는다.
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
            new AiChatProperties.TokenBudget(120000, 512),
            new AiChatProperties.Streaming(120, 30, 150, 60, 256)
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

    @Test
    void 응답의_종료_사유와_사용량을_청크_DTO_까지_그대로_옮긴다() {
        ChatResponse chatResponse = chatResponse("조각", "STOP", new DefaultUsage(10, 5, 15));

        AiChatStreamChunk chunk = aiChatClient.toStreamChunk(chatResponse);

        assertThat(chunk.delta()).isEqualTo("조각");
        assertThat(chunk.finishReason()).isEqualTo("STOP");
        assertThat(chunk.inputTokens()).isEqualTo(10);
        assertThat(chunk.outputTokens()).isEqualTo(5);
        assertThat(chunk.totalTokens()).isEqualTo(15);
        assertThat(chunk.hasValidUsage()).isTrue();
    }

    @Test
    void 잘린_응답의_종료_사유도_바꾸지_않고_그대로_보존한다() {
        ChatResponse chatResponse = chatResponse("조각", "LENGTH", new DefaultUsage(10, 5, 15));

        assertThat(aiChatClient.toStreamChunk(chatResponse).finishReason()).isEqualTo("LENGTH");
    }

    @Test
    void 본문_없이_종료_사유만_실린_응답은_메타데이터_전용_청크가_된다() {
        ChatResponse chatResponse = chatResponse("", "STOP", new DefaultUsage(0, 0, 0));

        AiChatStreamChunk chunk = aiChatClient.toStreamChunk(chatResponse);

        assertThat(chunk.isMetadataOnly()).isTrue();
        assertThat(chunk.finishReason()).isEqualTo("STOP");
        // 중간 청크에 채워져 오는 0 짜리 사용량은 실측으로 인정하지 않는다.
        assertThat(chunk.hasValidUsage()).isFalse();
    }

    @Test
    void 종료_사유가_빈_문자열인_중간_청크는_사유_없음으로_옮긴다() {
        ChatResponse chatResponse = chatResponse("조각", "", new DefaultUsage(0, 0, 0));

        AiChatStreamChunk chunk = aiChatClient.toStreamChunk(chatResponse);

        assertThat(chunk.finishReason()).isNull();
        assertThat(chunk.hasFinishReason()).isFalse();
    }

    private ChatResponse chatResponse(String text, String finishReason, DefaultUsage usage) {
        Generation generation = new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(
                List.of(generation),
                ChatResponseMetadata.builder().usage(usage).build());
    }
}
