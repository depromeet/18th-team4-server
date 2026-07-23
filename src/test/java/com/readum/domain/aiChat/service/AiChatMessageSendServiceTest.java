package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatCompletion;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.ChatTokenBudget;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatMessageSendServiceTest {

    private static final Long USER_ID = 1L;
    private static final ChatTokenBudget.Result.Granted GRANTED = new ChatTokenBudget.Result.Granted(0L, 100);

    @Mock
    private AiChatMessagePersistService persistService;

    @Mock
    private AiChatClient aiChatClient;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private InputModerationClient inputModerationClient;

    @Mock
    private ChatTokenBudget chatTokenBudget;

    // 결정적 test double: settle 산술 배선만 검증한다(실제 jtokkit 정확도는 JtokkitTokenCounterTest 담당).
    // 기존 단언값 유지를 위해 구 추정과 동일한 문자÷2.5 로 센다.
    private final TokenCounter tokenCounter = text ->
            (text == null || text.isEmpty()) ? 0 : (int) Math.ceil(text.length() / 2.5);

    private final AiChatProperties aiChatProperties = new AiChatProperties(
            new AiChatProperties.Context(8000, 2000, 4000, 800),
            new AiChatProperties.MessageRule(1000),
            new AiChatProperties.RateLimit(10, 5),
            new AiChatProperties.TokenBudget(4, 20000, 512)
    );

    private AiChatMessageSendService service;

    @BeforeEach
    void setUp() {
        // 입력 가드레일 기본값: 통과. 차단/장애 케이스는 각 테스트에서 override.
        lenient().when(inputModerationClient.check(anyString(), any()))
                .thenReturn(InputModerationResult.passed());
        // 토큰 예산 기본값: 예약 허용. 거절/우회 케이스는 각 테스트에서 override.
        lenient().when(chatTokenBudget.reserve(anyLong(), anyInt()))
                .thenReturn(GRANTED);
        service = new AiChatMessageSendService(
                persistService, aiChatClient, aiChatProperties,
                aiChatMessageRepository, userBookRepository, bookRepository, inputModerationClient,
                chatTokenBudget, tokenCounter
        );
    }

    private void givenLoadHistory(Long sessionId, List<HistoryMessage> history, Long userBookId) {
        givenLoadHistory(sessionId, null, history, userBookId);
    }

    private void givenLoadHistory(Long sessionId, String contextSummary, List<HistoryMessage> history, Long userBookId) {
        given(persistService.loadHistory(sessionId, USER_ID))
                .willReturn(new AiChatMessagePersistService.MessageLoadResult(contextSummary, history, userBookId));
    }

    private AiChatCompletion completion(String content, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        return new AiChatCompletion(content, inputTokens, outputTokens, totalTokens, null);
    }

    private List<MessageStreamEvent> executeTurn(SendMessageCommand command) {
        AiChatMessageSendService.PreparedChatTurn prepared = service.prepare(command);
        return service.generateAndPersist(prepared);
    }

    @Test
    void 빈_본문이면_BadRequest_MESSAGE_CONTENT_BLANK() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "   ");

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void NBSP_등_유니코드_공백만_있으면_BadRequest_MESSAGE_CONTENT_BLANK() {
        // U+00A0 NBSP, U+202F Narrow No-Break Space, U+2007 Figure Space 세 종류
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "   ");

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void 본문이_1001자면_BadRequest_MESSAGE_CONTENT_TOO_LONG() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "가".repeat(1001));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void 최근_USER_메시지_카운트가_한도_미만이면_rate_limit_검사를_통과한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(1L), any(LocalDateTime.class)))
                .willReturn(4L);
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 1, 1, 2));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(
                        1L, 7L, "응답", null, 1, 1, 2
                ));

        assertThatCode(() -> executeTurn(command)).doesNotThrowAnyException();
    }

    @Test
    void 최근_USER_메시지_카운트가_한도_도달이면_TooManyRequestsException_을_던진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(1L), any(LocalDateTime.class)))
                .willReturn(5L);

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
        verify(aiChatClient, never()).generate(any(AiChatStreamCommand.class));
    }

    @Test
    void 한도_초과_시_RateLimitInfo_에_retryAfter_와_limit_정보가_담긴다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(1L), any(LocalDateTime.class)))
                .willReturn(7L);

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .satisfies(ex -> {
                    RateLimitInfo info = ex.getRateLimitInfo();
                    assertThat(info).isNotNull();
                    assertThat(info.retryAfter()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(info.limitRequests()).isEqualTo(5L);
                    assertThat(info.remainingRequests()).isZero();
                });
    }

    @Test
    void rate_limit_카운트_쿼리는_count_period_초만큼_과거_시점부터_조회한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(aiChatMessageRepository.countRecentUserMessagesByOwner(eq(1L), any(LocalDateTime.class)))
                .willReturn(0L);
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 1, 1, 2));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(
                        1L, 7L, "응답", null, 1, 1, 2
                ));

        LocalDateTime before = LocalDateTime.now().minusSeconds(10);
        executeTurn(command);
        LocalDateTime after = LocalDateTime.now().minusSeconds(10);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiChatMessageRepository).countRecentUserMessagesByOwner(eq(1L), captor.capture());
        assertThat(captor.getValue()).isBetween(before, after);
    }

    @Test
    void 예산이_소진되면_429_USER_TOKEN_BUDGET_EXCEEDED_와_RetryAfter() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(chatTokenBudget.reserve(anyLong(), anyInt()))
                .willReturn(new ChatTokenBudget.Result.Denied(Duration.ofMinutes(90)));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.USER_TOKEN_BUDGET_EXCEEDED);
                    assertThat(ex.getRateLimitInfo().retryAfter()).isEqualTo(Duration.ofMinutes(90));
                    assertThat(ex.getRateLimitInfo().limitTokens()).isEqualTo(20000L);
                    assertThat(ex.getRateLimitInfo().remainingTokens()).isZero();
                });
        // 거절이면 USER 메시지를 저장하지 않고 생성도 시작하지 않는다.
        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(aiChatClient, never()).generate(any(AiChatStreamCommand.class));
    }

    @Test
    void 생성_정상_완료시_실측_출력_토큰으로_보정한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문"); // 2자 → 입력 추정 1
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 10, 5, 15)); // 실측 출력 5
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(1L, 7L, "응답", null, 10, 5, 15));

        executeTurn(command);

        // 사용자 계상 = 메시지 입력 추정(1) + 실측 출력(5) = 6
        verify(chatTokenBudget).settle(eq(USER_ID), eq(GRANTED), eq(6));
    }

    @Test
    void 생성_에러면_예약을_전액_환불한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willThrow(new RuntimeException("boom"));

        executeTurn(command); // 예외를 던지지 않고 error 이벤트로 변환

        // 에러는 사용자 과실이 아니므로 예약 전액 환불 → actualTotal=0 (예약분 그대로 차감)
        verify(chatTokenBudget).settle(eq(USER_ID), eq(GRANTED), eq(0));
    }

    @Test
    void 게이트_거절_시_예약을_전액_환불하고_예외를_그대로_던진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST))
                .given(aiChatClient).acquireRateLimitPermit(any(AiChatStreamCommand.class));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);

        verify(chatTokenBudget).settle(eq(USER_ID), eq(GRANTED), eq(0));
        verify(aiChatClient, never()).generate(any(AiChatStreamCommand.class));
    }

    @Test
    void Redis_우회시_채팅은_진행되고_보정은_생략된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(chatTokenBudget.reserve(anyLong(), anyInt()))
                .willReturn(new ChatTokenBudget.Result.Bypassed());
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 10, 5, 15));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(1L, 7L, "응답", null, 10, 5, 15));

        executeTurn(command);

        // Bypassed(예약 없음) → 보정할 예약이 없어 settle 미호출
        verify(chatTokenBudget, never()).settle(anyLong(), any(), anyInt());
    }

    @Test
    void 사전_단계에서_NotFoundException_이_던져지면_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(persistService.loadHistory(7L, 1L))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 사전_단계에서_BadRequestException_SESSION_LOCKED_도_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        given(persistService.loadHistory(7L, 1L))
                .willThrow(new BadRequestException(AiChatErrorCode.SESSION_LOCKED));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_LOCKED);
    }

    @Test
    void 입력_가드레일에_차단되면_REJECTED_저장_후_BadRequest_를_던지고_생성은_시작하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("차단 대상"), any()))
                .willReturn(InputModerationResult.blocked(List.of("self-harm")));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT);

        verify(persistService).recordRejectedUserMessage(7L, "차단 대상");
        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(aiChatClient, never()).generate(any(AiChatStreamCommand.class));
    }

    @Test
    void 입력_가드레일_차단_응답_메시지는_거부_정본_텍스트다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("차단 대상"), any()))
                .willReturn(InputModerationResult.blocked(List.of("sexual-minors")));

        assertThatThrownBy(() -> service.prepare(command))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요.");
    }

    @Test
    void Moderation_API_장애_UNAVAILABLE_이면_저장없이_ServiceUnavailable_을_던진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("질문"), any()))
                .willReturn(InputModerationResult.serviceUnavailable("OpenAI Moderation 502"));

        assertThatThrownBy(() -> service.prepare(command))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceUnavailableException.class))
                .extracting(ServiceUnavailableException::getErrorCode)
                .isEqualTo(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);

        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(persistService, never()).recordRejectedUserMessage(anyLong(), anyString());
        verify(aiChatClient, never()).generate(any(AiChatStreamCommand.class));
    }

    @Test
    void 생성이_정상이면_Token_과_Done_이벤트가_순서대로_반환되고_USER_와_ASSISTANT_가_저장된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "주제 요약");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("이 책은 자연 앞에서 인간의 한계를 그립니다.", 312, 58, 370));

        AiChatMessage savedAssistant = AiChatMessageFixture.persistedAssistantMessage(
                42L, sessionId, "이 책은 자연 앞에서 인간의 한계를 그립니다.", null,
                312, 58, 370
        );
        given(persistService.saveAssistantSuccess(eq(sessionId), eq("이 책은 자연 앞에서 인간의 한계를 그립니다."), any()))
                .willReturn(savedAssistant);

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(2);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Token.class))
                .extracting(MessageStreamEvent.Token::delta)
                .isEqualTo("이 책은 자연 앞에서 인간의 한계를 그립니다.");
        assertThat(events.get(1))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Done.class))
                .satisfies(done -> {
                    assertThat(done.tokenCount().total()).isEqualTo(370);
                    assertThat(done.tokenCount().input()).isEqualTo(312);
                    assertThat(done.tokenCount().output()).isEqualTo(58);
                });

        // 통과 시 USER 메시지가 COMPLETED 로 저장됨
        verify(persistService, times(1)).recordUserMessage(sessionId, 1L, "주제 요약");
        verify(persistService, never()).recordRejectedUserMessage(anyLong(), anyString());
        verify(persistService, times(1))
                .saveAssistantSuccess(eq(sessionId), eq("이 책은 자연 앞에서 인간의 한계를 그립니다."), any());
        verify(persistService, never())
                .saveAssistantFailed(anyLong(), anyString(), any());
    }

    @Test
    void 생성_도중_에러가_나면_FAILED_가_저장되고_Error_이벤트가_반환된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willThrow(new RuntimeException("connection reset"));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .extracting(MessageStreamEvent.Error::code)
                .isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());

        verify(persistService, times(1)).saveAssistantFailed(eq(sessionId), eq(""), isNull());
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
    }

    @Test
    void TooManyRequestsException_BURST_이면_AI_RATE_LIMIT_BURST_코드와_RateLimitInfo_가_Error_이벤트로_운반된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(13), null, null, null, null,
                Duration.ofSeconds(12), null
        );
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, info));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST.name());
                    assertThat(error.rateLimitInfo()).isSameAs(info);
                });
    }

    @Test
    void TooManyRequestsException_QUOTA_EXHAUSTED_이면_AI_QUOTA_EXHAUSTED_코드의_Error_이벤트가_반환된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED.name());
                    assertThat(error.rateLimitInfo()).isNull();
                });
    }

    @Test
    void 이전_이력이_있으면_LLM_호출_시_history_와_현재_user_메시지가_함께_전달된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "이번 질문");

        givenLoadHistory(sessionId, List.of(
                new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
        ), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 1, 1, 2));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(
                        1L, sessionId, "응답", null, 1, 1, 2
                ));

        executeTurn(command);

        ArgumentCaptor<AiChatStreamCommand> captor = ArgumentCaptor.forClass(AiChatStreamCommand.class);
        verify(aiChatClient).generate(captor.capture());
        List<HistoryMessage> sentHistory = captor.getValue().history();
        assertThat(sentHistory).hasSize(3);
        assertThat(sentHistory.get(0).content()).isEqualTo("이전 질문");
        assertThat(sentHistory.get(1).content()).isEqualTo("이전 응답");
        assertThat(sentHistory.get(2).content()).isEqualTo("이번 질문");
        assertThat(sentHistory.get(2).role()).isEqualTo(HistoryMessage.Role.USER);
    }

    @Test
    void 누적_요약이_있으면_AiChatStreamCommand_에_contextSummary_로_전달된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "이번 질문");

        givenLoadHistory(sessionId, "이전 대화를 압축한 누적 요약", List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 1, 1, 2));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(1L, sessionId, "응답", null, 1, 1, 2));

        executeTurn(command);

        ArgumentCaptor<AiChatStreamCommand> captor = ArgumentCaptor.forClass(AiChatStreamCommand.class);
        verify(aiChatClient).generate(captor.capture());
        assertThat(captor.getValue().contextSummary()).isEqualTo("이전 대화를 압축한 누적 요약");
    }

    @Test
    void 생성_타임아웃_등_미분류_IO_예외는_AI_STREAM_INTERRUPTED_error_이벤트가_된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        // RestClient I/O 실패(읽기 타임아웃 포함)는 ResourceAccessException 으로 올라온다.
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willThrow(new org.springframework.web.client.ResourceAccessException("read timeout"));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        MessageStreamEvent.Error error = (MessageStreamEvent.Error) events.get(0);
        assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());
    }

    @Test
    void 정산이_실패해도_성공_이벤트는_그대로_반환된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generate(any(AiChatStreamCommand.class)))
                .willReturn(completion("응답", 10, 5, 15));
        given(persistService.saveAssistantSuccess(anyLong(), anyString(), any()))
                .willReturn(AiChatMessageFixture.persistedAssistantMessage(1L, sessionId, "응답", null, 10, 5, 15));
        willThrow(new RuntimeException("redis down"))
                .given(chatTokenBudget).settle(anyLong(), any(), anyInt());

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(2);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Token.class))
                .extracting(MessageStreamEvent.Token::delta)
                .isEqualTo("응답");
    }
}
