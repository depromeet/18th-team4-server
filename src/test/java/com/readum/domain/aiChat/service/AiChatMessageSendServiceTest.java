package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.ratelimit.AiChatRateLimiter;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatMessageSendServiceTest {

    @Mock
    private AiChatMessagePersistService persistService;

    @Mock
    private AiChatClient aiChatClient;

    @Mock
    private AiChatRateLimiter aiChatRateLimiter;

    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.ContextWindow(20),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5)
    );

    private AiChatMessageSendService service;

    @BeforeEach
    void setUp() {
        service = new AiChatMessageSendService(persistService, aiChatClient, aiChatProperties, aiChatRateLimiter);
    }

    @Test
    void 빈_본문이면_BadRequest_MESSAGE_CONTENT_BLANK() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "   ");

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistoryAndRecordUserMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void NBSP_등_유니코드_공백만_있으면_BadRequest_MESSAGE_CONTENT_BLANK() {
        // Character.isWhitespace() 가 빠뜨리는 NBSP(U+00A0) / Narrow NBSP(U+202F) /
        // Figure Space(U+2007) 만 들어와도 빈 본문으로 거절되어야 한다.
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "    ");

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistoryAndRecordUserMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void 본문이_1001자면_BadRequest_MESSAGE_CONTENT_TOO_LONG() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "가".repeat(1001));

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);

        verify(persistService, never()).loadHistoryAndRecordUserMessage(anyLong(), anyLong(), anyString());
    }

    @Test
    void rate_limiter_가_한도_초과를_던지면_persist_도_LLM_호출도_없이_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "질문");
        TooManyRequestsException thrown = new TooManyRequestsException(
                AiChatErrorCode.USER_RATE_LIMIT_BURST,
                new RateLimitInfo(java.time.Duration.ofSeconds(10), 5L, null, 0L, null, null, null)
        );
        org.mockito.BDDMockito.willThrow(thrown).given(aiChatRateLimiter).check(1L);

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_RATE_LIMIT_BURST);

        verify(persistService, never()).loadHistoryAndRecordUserMessage(anyLong(), anyLong(), anyString());
        verify(aiChatClient, never()).stream(any(AiChatStreamCommand.class));
    }

    @Test
    void 사전_단계에서_NotFoundException_이_던져지면_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "질문");
        given(persistService.loadHistoryAndRecordUserMessage(7L, 1L, "질문"))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 사전_단계에서_BadRequestException_SESSION_CLOSED_도_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "질문");
        given(persistService.loadHistoryAndRecordUserMessage(7L, 1L, "질문"))
                .willThrow(new BadRequestException(AiChatErrorCode.SESSION_CLOSED));

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_CLOSED);
    }

    @Test
    void 정상_스트림이면_Token_여러개와_Done_이벤트가_방출되고_ASSISTANT_가_저장된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "주제 요약");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "주제 요약"))
                .willReturn(List.of());

        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Token("이 책은"),
                new AiChatChunk.Token(" 자연 앞에서"),
                new AiChatChunk.Token(" 인간의 한계를 그립니다."),
                new AiChatChunk.Completion(312, 58, 370, null)
        ));

        AiChatMessage savedAssistant = AiChatMessage.of(
                42L, sessionId, AiChatMessage.Role.ASSISTANT,
                "이 책은 자연 앞에서 인간의 한계를 그립니다.", null,
                312, 58, 370,
                AiChatMessage.Status.COMPLETED, LocalDateTime.of(2026, 5, 2, 14, 0, 0)
        );
        given(persistService.saveAssistantSuccess(eq(sessionId), eq("이 책은 자연 앞에서 인간의 한계를 그립니다."), any()))
                .willReturn(savedAssistant);

        StepVerifier.create(service.execute(command))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Done.class);
                    MessageStreamEvent.Done done = (MessageStreamEvent.Done) event;
                    assertThat(done.messageId()).isEqualTo(42L);
                    assertThat(done.tokenCount().total()).isEqualTo(370);
                    assertThat(done.tokenCount().input()).isEqualTo(312);
                    assertThat(done.tokenCount().output()).isEqualTo(58);
                })
                .verifyComplete();

        verify(persistService, times(1))
                .saveAssistantSuccess(eq(sessionId), eq("이 책은 자연 앞에서 인간의 한계를 그립니다."), any());
        verify(persistService, never())
                .saveAssistantFailed(anyLong(), anyString(), any());
    }

    @Test
    void 스트림_도중_에러가_나면_FAILED_가_저장되고_Error_이벤트가_방출된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());

        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.concat(
                Flux.just(new AiChatChunk.Token("부분 응답")),
                Flux.error(new RuntimeException("connection reset"))
        ));

        StepVerifier.create(service.execute(command))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Error.class);
                    MessageStreamEvent.Error error = (MessageStreamEvent.Error) event;
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());
                })
                .verifyComplete();

        verify(persistService, times(1))
                .saveAssistantFailed(eq(sessionId), eq("부분 응답"), any());
        verify(persistService, never())
                .saveAssistantSuccess(anyLong(), anyString(), any());
    }

    @Test
    void TooManyRequestsException_BURST_이면_AI_RATE_LIMIT_BURST_코드와_RateLimitInfo_가_Error_이벤트로_운반된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());
        RateLimitInfo info = new RateLimitInfo(
                java.time.Duration.ofSeconds(13), null, null, null, null,
                java.time.Duration.ofSeconds(12), null
        );
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.error(
                new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, info)
        ));

        StepVerifier.create(service.execute(command))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Error.class);
                    MessageStreamEvent.Error error = (MessageStreamEvent.Error) event;
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST.name());
                    assertThat(error.rateLimitInfo()).isSameAs(info);
                })
                .verifyComplete();
    }

    @Test
    void TooManyRequestsException_QUOTA_EXHAUSTED_이면_AI_QUOTA_EXHAUSTED_코드의_Error_이벤트가_방출된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.error(
                new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED)
        ));

        StepVerifier.create(service.execute(command))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Error.class);
                    MessageStreamEvent.Error error = (MessageStreamEvent.Error) event;
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED.name());
                    assertThat(error.rateLimitInfo()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void 토큰_도중_클라이언트가_disconnect_하면_부분_응답이_FAILED_로_저장된다() throws InterruptedException {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());
        // 청크 emit 시점을 테스트가 직접 제어하기 위한 sink (wall-clock race 회피).
        Sinks.Many<AiChatChunk> sink = Sinks.many().unicast().onBackpressureBuffer();
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(sink.asFlux());

        // boundedElastic 비동기 영속화 완료를 기다리기 위한 latch
        CountDownLatch persisted = new CountDownLatch(1);
        willAnswer(invocation -> {
            persisted.countDown();
            return null;
        }).given(persistService).saveAssistantFailed(eq(sessionId), eq("부분 응답"), isNull());

        StepVerifier.create(service.execute(command))
                .then(() -> sink.tryEmitNext(new AiChatChunk.Token("부분 응답")))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .thenCancel()
                .verify();

        assertThat(persisted.await(2, TimeUnit.SECONDS))
                .as("cancel 후 boundedElastic 의 saveAssistantFailed 호출이 일어나야 함")
                .isTrue();
        verify(persistService, times(1)).saveAssistantFailed(eq(sessionId), eq("부분 응답"), isNull());
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
    }

    @Test
    void Completion_메타까지_받은_뒤_disconnect_하면_COMPLETED_로_저장된다() throws InterruptedException {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());
        Sinks.Many<AiChatChunk> sink = Sinks.many().unicast().onBackpressureBuffer();
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(sink.asFlux());

        AiChatMessage savedAssistant = AiChatMessage.of(
                42L, sessionId, AiChatMessage.Role.ASSISTANT, "응답", null,
                10, 5, 15, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
        );
        CountDownLatch persisted = new CountDownLatch(1);
        willAnswer(invocation -> {
            persisted.countDown();
            return savedAssistant;
        }).given(persistService).saveAssistantSuccess(eq(sessionId), eq("응답"), any());

        // Token emit → 다운스트림 onNext 까지 동기 전파됨 → assertNext 가 받음.
        // Completion emit → concatMap 이 동기적으로 completionRef.set 까지 처리.
        // tryEmitNext 가 반환된 시점에 모든 상태가 결정되므로 thenCancel 이 항상
        // completionRef != null 인 상태에서 실행된다.
        StepVerifier.create(service.execute(command))
                .then(() -> sink.tryEmitNext(new AiChatChunk.Token("응답")))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .then(() -> sink.tryEmitNext(new AiChatChunk.Completion(10, 5, 15, null)))
                .thenCancel()
                .verify();

        assertThat(persisted.await(2, TimeUnit.SECONDS))
                .as("cancel 후 boundedElastic 의 saveAssistantSuccess 호출이 일어나야 함")
                .isTrue();
        verify(persistService, times(1)).saveAssistantSuccess(eq(sessionId), eq("응답"), any());
        verify(persistService, never()).saveAssistantFailed(anyLong(), anyString(), any());
    }

    @Test
    void 첫_토큰도_받기_전에_disconnect_하면_ASSISTANT_메시지를_저장하지_않는다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "질문"))
                .willReturn(List.of());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.never());

        // 어떤 청크도 emit 되지 않은 상태에서 cancel.
        // doOnCancel 의 skip 분기가 동기 early-return 이라 별도 wait 불필요.
        StepVerifier.create(service.execute(command))
                .expectSubscription()
                .thenCancel()
                .verify();

        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
        verify(persistService, never()).saveAssistantFailed(anyLong(), anyString(), any());
    }

    @Test
    void 이전_이력이_있으면_LLM_호출_시_history_와_현재_user_메시지가_함께_전달된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(1L, sessionId, "이번 질문");

        given(persistService.loadHistoryAndRecordUserMessage(sessionId, 1L, "이번 질문"))
                .willReturn(List.of(
                        new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                        new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
                ));
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Completion(1, 1, 2, null)
        ));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessage.of(
                        1L, sessionId, AiChatMessage.Role.ASSISTANT, "", null,
                        1, 1, 2, AiChatMessage.Status.COMPLETED, LocalDateTime.now()
                ));

        StepVerifier.create(service.execute(command))
                .expectNextCount(1)
                .verifyComplete();

        org.mockito.ArgumentCaptor<AiChatStreamCommand> captor =
                org.mockito.ArgumentCaptor.forClass(AiChatStreamCommand.class);
        verify(aiChatClient).stream(captor.capture());
        List<HistoryMessage> sentHistory = captor.getValue().history();
        assertThat(sentHistory).hasSize(3);
        assertThat(sentHistory.get(0).content()).isEqualTo("이전 질문");
        assertThat(sentHistory.get(1).content()).isEqualTo("이전 응답");
        assertThat(sentHistory.get(2).content()).isEqualTo("이번 질문");
        assertThat(sentHistory.get(2).role()).isEqualTo(HistoryMessage.Role.USER);
    }
}
