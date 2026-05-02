package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.history.ChatHistoryBuilder;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatMessageSendServiceTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private ChatHistoryBuilder chatHistoryBuilder;

    @Mock
    private AiChatClient aiChatClient;

    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.ContextWindow(20),
            new AiChatProperties.MessageRule(1000)
    );

    private final TransactionTemplate transactionTemplate = new TransactionTemplate(new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    });

    private AiChatMessageSendService service;

    @BeforeEach
    void setUp() {
        service = new AiChatMessageSendService(
                aiChatSessionRepository,
                aiChatMessageRepository,
                chatHistoryBuilder,
                aiChatClient,
                aiChatProperties,
                transactionTemplate
        );
    }

    @Test
    void 소유권_없는_세션이면_NotFoundException_을_던지고_USER_메시지는_저장되지_않는다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "질문");

        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void 종료된_세션이면_BadRequest_를_던지고_USER_메시지는_저장되지_않는다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "질문");

        AiChatSession closed = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.CLOSED,
                0, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId))
                .willReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_CLOSED);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void 빈_본문이면_BadRequest_MESSAGE_CONTENT_BLANK() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "   ");

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);
    }

    @Test
    void 본문이_1001자면_BadRequest_MESSAGE_CONTENT_TOO_LONG() {
        SendMessageCommand command = new SendMessageCommand(1L, 7L, "가".repeat(1001));

        assertThatThrownBy(() -> service.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);
    }

    @Test
    void 정상_스트림이면_Token_여러개와_Done_이벤트가_방출되고_ASSISTANT_가_저장된다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "주제 요약");

        givenOwnedActiveSession(sessionId, userId);
        given(chatHistoryBuilder.buildPreviousHistory(eq(sessionId)))
                .willReturn(List.of());

        // LLM 이 plain text 로 응답: 토큰을 잘게 쪼개서 전달
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Token("이 책은"),
                new AiChatChunk.Token(" 자연 앞에서"),
                new AiChatChunk.Token(" 인간의 한계를 그립니다."),
                new AiChatChunk.Completion(312, 58, 370, null)
        ));

        AtomicLong idSeq = new AtomicLong(1);
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    idSeq.getAndIncrement(),
                    incoming.getSessionId(),
                    incoming.getRole(),
                    incoming.getContent(),
                    incoming.getQuoteText(),
                    incoming.getInputTokens(),
                    incoming.getOutputTokens(),
                    incoming.getTotalTokens(),
                    incoming.getStatus(),
                    incoming.getCreatedAt()
            );
        });

        StepVerifier.create(service.execute(command))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Done.class);
                    MessageStreamEvent.Done done = (MessageStreamEvent.Done) event;
                    assertThat(done.tokenCount().total()).isEqualTo(370);
                    assertThat(done.tokenCount().input()).isEqualTo(312);
                    assertThat(done.tokenCount().output()).isEqualTo(58);
                })
                .verifyComplete();

        ArgumentCaptor<AiChatMessage> savedCaptor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository, org.mockito.Mockito.times(2)).save(savedCaptor.capture());
        List<AiChatMessage> saves = savedCaptor.getAllValues();

        AiChatMessage userMessage = saves.get(0);
        assertThat(userMessage.getRole()).isEqualTo(AiChatMessage.Role.USER);
        assertThat(userMessage.getStatus()).isEqualTo(AiChatMessage.Status.COMPLETED);
        assertThat(userMessage.getContent()).isEqualTo("주제 요약");

        AiChatMessage assistantMessage = saves.get(1);
        assertThat(assistantMessage.getRole()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(assistantMessage.getStatus()).isEqualTo(AiChatMessage.Status.COMPLETED);
        assertThat(assistantMessage.getContent()).isEqualTo("이 책은 자연 앞에서 인간의 한계를 그립니다.");
        assertThat(assistantMessage.getQuoteText()).isNull();
        assertThat(assistantMessage.getTotalTokens()).isEqualTo(370);
    }

    @Test
    void buildPreviousHistory_는_USER_메시지_save_전에_호출된다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "질문");

        givenOwnedActiveSession(sessionId, userId);
        given(chatHistoryBuilder.buildPreviousHistory(eq(sessionId)))
                .willReturn(List.of());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Token("응답"),
                new AiChatChunk.Completion(1, 1, 2, null)
        ));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    1L,
                    incoming.getSessionId(),
                    incoming.getRole(),
                    incoming.getContent(),
                    incoming.getQuoteText(),
                    incoming.getInputTokens(),
                    incoming.getOutputTokens(),
                    incoming.getTotalTokens(),
                    incoming.getStatus(),
                    incoming.getCreatedAt()
            );
        });

        StepVerifier.create(service.execute(command))
                .expectNextCount(2)
                .verifyComplete();

        // buildPreviousHistory 가 USER 메시지 save 보다 먼저 호출되어야 함
        // (Hibernate auto-flush 로 인한 USER 메시지 컨텍스트 중복 회피)
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(chatHistoryBuilder, aiChatMessageRepository);
        inOrder.verify(chatHistoryBuilder).buildPreviousHistory(sessionId);
        inOrder.verify(aiChatMessageRepository, atLeastOnce()).save(any(AiChatMessage.class));
    }

    @Test
    void 스트림_도중_에러가_나면_FAILED_ASSISTANT_가_저장되고_Error_이벤트가_방출된다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "질문");

        givenOwnedActiveSession(sessionId, userId);
        given(chatHistoryBuilder.buildPreviousHistory(eq(sessionId)))
                .willReturn(List.of());

        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.concat(
                Flux.just(new AiChatChunk.Token("부분 응답")),
                Flux.error(new RuntimeException("connection reset"))
        ));

        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    1L,
                    incoming.getSessionId(),
                    incoming.getRole(),
                    incoming.getContent(),
                    incoming.getQuoteText(),
                    incoming.getInputTokens(),
                    incoming.getOutputTokens(),
                    incoming.getTotalTokens(),
                    incoming.getStatus(),
                    incoming.getCreatedAt()
            );
        });

        StepVerifier.create(service.execute(command))
                .assertNext(event -> assertThat(event).isInstanceOf(MessageStreamEvent.Token.class))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Error.class);
                    MessageStreamEvent.Error error = (MessageStreamEvent.Error) event;
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());
                })
                .verifyComplete();

        ArgumentCaptor<AiChatMessage> savedCaptor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository, org.mockito.Mockito.times(2)).save(savedCaptor.capture());
        AiChatMessage failedAssistant = savedCaptor.getAllValues().get(1);
        assertThat(failedAssistant.getRole()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(failedAssistant.getStatus()).isEqualTo(AiChatMessage.Status.FAILED);
        assertThat(failedAssistant.getContent()).isEqualTo("부분 응답");
    }

    @Test
    void TooManyRequestsException_이면_AI_RATE_LIMIT_EXCEEDED_코드의_Error_이벤트가_방출된다() {
        Long userId = 1L;
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(userId, sessionId, "질문");

        givenOwnedActiveSession(sessionId, userId);
        given(chatHistoryBuilder.buildPreviousHistory(eq(sessionId)))
                .willReturn(List.of());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.error(
                new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED)
        ));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    1L,
                    incoming.getSessionId(),
                    incoming.getRole(),
                    incoming.getContent(),
                    incoming.getQuoteText(),
                    incoming.getInputTokens(),
                    incoming.getOutputTokens(),
                    incoming.getTotalTokens(),
                    incoming.getStatus(),
                    incoming.getCreatedAt()
            );
        });

        StepVerifier.create(service.execute(command))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(MessageStreamEvent.Error.class);
                    MessageStreamEvent.Error error = (MessageStreamEvent.Error) event;
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_EXCEEDED.name());
                })
                .verifyComplete();
    }

    private void givenOwnedActiveSession(Long sessionId, Long userId) {
        AiChatSession active = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                0, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId))
                .willReturn(Optional.of(active));
        org.mockito.Mockito.lenient()
                .when(aiChatSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(active));
    }
}
