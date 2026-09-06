package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.aiChat.out.UserMessageRateLimiter;
import com.readum.domain.aiChat.stream.ChatDeliveryChannel;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AiChatMessageSendServiceTest {

    private static final Long USER_ID = 1L;
    private static final String REQUEST_ID = "0f2f1c9a-9f4d-4b2b-8f0d-6e0b0e7d5a11";
    private static final Long TURN_REQUEST_ID = 4242L;
    private static final UserTokenBudgetWriter.ReserveResult.Granted GRANTED =
            new UserTokenBudgetWriter.ReserveResult.Granted(20260904, 100);
    private static final AiChatClient.RateLimitPermit GATE_PERMIT =
            new AiChatClient.RateLimitPermit.Counted("gpt-4o-mini", 29_000_000L, 1000);
    private static final LocalDateTime SAVED_AT = LocalDateTime.of(2026, 9, 6, 12, 0, 0);
    /** 기한 넷과 큐 상한 — 운영 후보값 그대로. 기한을 짧게 둬야 하는 테스트는 따로 서비스를 만든다. */
    private static final AiChatProperties.Streaming STREAMING =
            new AiChatProperties.Streaming(120, 30, 150, 60, 60, 60, 256);

    @Mock
    private AiChatMessagePersistService persistService;

    @Mock
    private AiChatClient aiChatClient;

    @Mock
    private UserMessageRateLimiter userMessageRateLimiter;

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private InputModerationClient inputModerationClient;

    @Mock
    private UserTokenBudgetWriter userTokenBudgetWriter;

    @Mock
    private AiChatTurnRequestWriter aiChatTurnRequestWriter;

    @Mock
    private AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;

    // 후처리 실행기 대역: 제출을 그 자리에서 실행해 테스트를 결정적으로 만든다.
    // 제출 거절(종료 절차) 경로만 테스트별로 던지게 바꾼다.
    @Mock
    private ExecutorService aiChatPostProcessingExecutor;

    // 진행 목록은 순수 메모리 상태라 진짜를 쓴다 — 등록·정리가 실제로 맞물리는지 보려면 대역이 도움이 되지 않는다.
    private final AiChatInFlightTurnRegistry aiChatInFlightTurnRegistry = new AiChatInFlightTurnRegistry();

    // 결정적 test double: settle 산술 배선만 검증한다(실제 jtokkit 정확도는 JtokkitTokenCounterTest 담당).
    // 기존 단언값 유지를 위해 구 추정과 동일한 문자÷2.5 로 센다.
    private final TokenCounter tokenCounter = text ->
            (text == null || text.isEmpty()) ? 0 : (int) Math.ceil(text.length() / 2.5);

    private final AiChatProperties aiChatProperties = propertiesWith(STREAMING);

    private AiChatMessageSendService service;

    private static AiChatProperties propertiesWith(AiChatProperties.Streaming streaming) {
        return new AiChatProperties(
                new AiChatProperties.Context(8000, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(120000, 512),
                streaming
        );
    }

    @BeforeEach
    void setUp() {
        // 입력 가드레일 기본값: 통과. 차단/장애 케이스는 각 테스트에서 override.
        lenient().when(inputModerationClient.check(anyString(), any()))
                .thenReturn(InputModerationResult.passed());
        // 요청 기록 기본값: 중복 아님(자리 확보 성공). 중복 케이스는 각 테스트에서 override.
        lenient().when(aiChatTurnRequestWriter.claim(anyLong(), anyLong(), anyString(), any(Duration.class)))
                .thenReturn(TURN_REQUEST_ID);
        // 토큰 예산 기본값: 예약 허용. 거절 케이스는 각 테스트에서 override.
        // 예약은 요청 기록의 예약 정보와 한 트랜잭션이라 AiChatTurnRequestWriter 를 거친다.
        lenient().when(aiChatTurnRequestWriter.reserveWithRecord(anyLong(), anyLong(), anyInt()))
                .thenReturn(GRANTED);
        // rate limit 기본값: 통과(Allowed). 거절/우회 케이스는 각 테스트에서 override.
        lenient().when(userMessageRateLimiter.tryConsume(anyLong()))
                .thenReturn(new UserMessageRateLimiter.Result.Allowed());
        // 전역 게이트 기본값: 계상된 permit 확보. 거절 케이스는 각 테스트에서 override.
        lenient().when(aiChatClient.acquireRateLimitPermit(any(AiChatStreamCommand.class)))
                .thenReturn(GATE_PERMIT);
        // 후처리 실행기 기본값: 제출받은 작업을 그 자리에서 실행. 거절 케이스는 각 테스트에서 override.
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(aiChatPostProcessingExecutor).execute(any(Runnable.class));
        service = serviceWith(aiChatProperties);
    }

    private AiChatMessageSendService serviceWith(AiChatProperties properties) {
        return new AiChatMessageSendService(
                persistService, aiChatClient, properties,
                userMessageRateLimiter, userBookRepository, bookRepository, inputModerationClient,
                aiChatTurnRequestWriter, aiChatTurnOutcomeWriter,
                aiChatInFlightTurnRegistry, aiChatPostProcessingExecutor, tokenCounter
        );
    }

    private void givenLoadHistory(Long sessionId, List<HistoryMessage> history, Long userBookId) {
        givenLoadHistory(sessionId, null, history, userBookId);
    }

    private void givenLoadHistory(Long sessionId, String contextSummary, List<HistoryMessage> history, Long userBookId) {
        given(persistService.loadHistory(sessionId, USER_ID))
                .willReturn(new AiChatMessagePersistService.MessageLoadResult(contextSummary, history, userBookId));
    }

    /** 본문 조각 1건 + 종료 사유 조각 + 실측 사용량이 실린 마지막 조각 — 실제 스트리밍 응답과 같은 모양. */
    private Flux<AiChatStreamChunk> streamOf(String content, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        return Flux.just(
                AiChatStreamChunk.ofDelta(content),
                AiChatStreamChunk.ofFinishReason("STOP"),
                AiChatStreamChunk.ofUsage(inputTokens, outputTokens, totalTokens));
    }

    /** 이번 턴이 저장·정산까지 확정됐다고 응답하는 요청 종료 트랜잭션. */
    private void givenCommittedSuccess(Long messageId, Integer input, Integer output, Integer total) {
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt()))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                        new AiChatTurnOutcomeWriter.SavedAssistantMessage(
                                messageId, input, output, total, SAVED_AT)));
    }

    /** 청구 없이 끝났다고 응답하는 요청 종료 트랜잭션 (예약 R 을 되돌린 결과). */
    private void givenFinishedWithoutCharge() {
        given(aiChatTurnOutcomeWriter.finishWithoutCharge(
                anyLong(), any(AiChatTurnRequest.Status.class), anyString()))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge(
                        AiChatTurnRequest.Status.FAILED, GRANTED.reservedTokens()));
    }

    /**
     * 한 턴을 선행 처리 → 생성 → 전달 채널 소비 순서로 끝까지 실행하고, 클라이언트가 받았을 이벤트를 돌려준다.
     * 선행 처리의 거절은 prepare 가 동기로 던지므로 이 호출에서 그대로 다시 던져진다.
     */
    private List<MessageStreamEvent> executeTurn(SendMessageCommand command) {
        return executeTurn(command, service);
    }

    private List<MessageStreamEvent> executeTurn(SendMessageCommand command, AiChatMessageSendService target) {
        ChatDeliveryChannel deliveryChannel = ChatDeliveryChannel.open(STREAMING);
        executeTurn(command, target, deliveryChannel);
        return drain(deliveryChannel);
    }

    private void executeTurn(
            SendMessageCommand command, AiChatMessageSendService target, ChatDeliveryChannel deliveryChannel) {
        AiChatMessageSendService.PreparedChatTurn turn = target.prepare(command);
        target.generateAndDeliver(turn, deliveryChannel);
    }

    /** 전달 스레드가 하는 일 — 채널에서 이벤트를 순서대로 꺼낸다. 종료 이벤트가 나오거나 더 없으면 끝낸다. */
    private List<MessageStreamEvent> drain(ChatDeliveryChannel deliveryChannel) {
        List<MessageStreamEvent> events = new ArrayList<>();
        try {
            while (true) {
                MessageStreamEvent event = deliveryChannel.poll(Duration.ZERO);
                if (event == null) {
                    return events;
                }
                events.add(event);
                if (!(event instanceof MessageStreamEvent.Token)) {
                    return events;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return events;
        }
    }

    @Test
    void 빈_본문이면_BadRequest_MESSAGE_CONTENT_BLANK() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "   ");

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void NBSP_등_유니코드_공백만_있으면_BadRequest_MESSAGE_CONTENT_BLANK() {
        // U+00A0 NBSP, U+202F Narrow No-Break Space, U+2007 Figure Space 세 종류
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "   ");

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_BLANK);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void 본문이_1001자면_BadRequest_MESSAGE_CONTENT_TOO_LONG() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "가".repeat(1001));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.MESSAGE_CONTENT_TOO_LONG);

        verify(persistService, never()).loadHistory(anyLong(), anyLong());
    }

    @Test
    void 같은_요청_식별자로_다시_보내면_409_로_거절하고_어떤_부수_효과도_시작하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        // 중복 판정은 조회가 아니라 (userId, requestId) UNIQUE 삽입이라, 중복은 유일 위반으로 드러난다.
        given(aiChatTurnRequestWriter.claim(anyLong(), anyLong(), anyString(), any(Duration.class)))
                .willThrow(new DataIntegrityViolationException("uk_ai_chat_turn_request_user_request"));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(ConflictException.class))
                .extracting(ConflictException::getErrorCode)
                .isEqualTo(AiChatErrorCode.DUPLICATE_TURN_REQUEST);

        // 새 생성·예약·과금을 시작하지 않는다 — 폭주 가드 슬롯도 소모하지 않는다.
        verifyNoInteractions(userMessageRateLimiter, persistService, inputModerationClient,
                aiChatClient, aiChatTurnOutcomeWriter);
        verify(aiChatTurnRequestWriter, never()).reserveWithRecord(anyLong(), anyLong(), anyInt());
    }

    @Test
    void 중복으로_거절한_요청은_먼저_받은_요청을_실패로_끝내지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(aiChatTurnRequestWriter.claim(anyLong(), anyLong(), anyString(), any(Duration.class)))
                .willThrow(new DataIntegrityViolationException("uk_ai_chat_turn_request_user_request"));

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(ConflictException.class);

        // 자리를 잡지 못했으므로 끝낼 행이 없다 — 먼저 받은 요청은 아직 진행 중일 수 있고,
        // 재전송이 그 요청의 상태를 건드리면 안 된다.
        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
    }

    @Test
    void 요청_중복_판정을_사용자_폭주_가드보다_먼저_수행한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        // 재전송이 폭주 슬롯을 갉아먹지 않으려면 중복 판정이 가드보다 앞서야 한다(슬롯은 반환하지 않는다).
        InOrder gateOrder = inOrder(aiChatTurnRequestWriter, userMessageRateLimiter);
        gateOrder.verify(aiChatTurnRequestWriter)
                .claim(eq(USER_ID), eq(7L), eq(REQUEST_ID), any(Duration.class));
        gateOrder.verify(userMessageRateLimiter).tryConsume(USER_ID);
    }

    @Test
    void 요청_기록의_만료_유예는_선행_처리_여유와_생성_전체_기한과_후처리_여유의_합이다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        // 접수 시각부터 한 턴이 정상적으로 끝나기까지 걸릴 수 있는 구간을 모두 덮어야, 정상 처리 중인 요청을
        // 만료 복구가 가로채 환불하지 않는다. 종료 대기 상한은 여기 들어가지 않는다 — 그것은 배포 때
        // 얼마나 기다려 줄지를 정하는 값이지 한 턴이 얼마나 걸리는지가 아니다.
        ArgumentCaptor<Duration> expiryTimeout = ArgumentCaptor.forClass(Duration.class);
        verify(aiChatTurnRequestWriter)
                .claim(eq(USER_ID), eq(7L), eq(REQUEST_ID), expiryTimeout.capture());
        assertThat(expiryTimeout.getValue()).isEqualTo(Duration.ofSeconds(60 + 120 + 60));
    }

    @Test
    void 예약_전_거절도_같은_종료_트랜잭션_한_번으로_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Denied());

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(TooManyRequestsException.class);

        // 예약 전이라 잠근 행에 예약 정보가 없다 — 되돌릴 양이 0 이 되므로 호출부가 예약 전후를 나누지 않는다.
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED.name());
        verify(userTokenBudgetWriter, never()).refund(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 예산_거절도_같은_종료_트랜잭션_한_번으로_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(aiChatTurnRequestWriter.reserveWithRecord(anyLong(), anyLong(), anyInt()))
                .willReturn(new UserTokenBudgetWriter.ReserveResult.Denied(Duration.ofMinutes(90)));

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(TooManyRequestsException.class);

        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.USER_TOKEN_BUDGET_EXCEEDED.name());
    }

    @Test
    void 예약_후_거절은_예약_반환과_상태_전이를_한_트랜잭션으로_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(anyString(), any()))
                .willReturn(InputModerationResult.blocked(List.of("self-harm")));

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(BadRequestException.class);

        // 환불과 상태 전이를 따로 부르지 않는다 — 둘이 갈리면 되돌리지 못한 예약이나 이중 환급이 생긴다.
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT.name());
        verify(userTokenBudgetWriter, never()).refund(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 요청_종료가_실패해도_원래_거절_응답을_그대로_내보낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Denied());
        given(aiChatTurnOutcomeWriter.finishWithoutCharge(
                anyLong(), any(AiChatTurnRequest.Status.class), anyString()))
                .willThrow(new DataIntegrityViolationException("boom"));

        // 실패 기록이 안 되면 그 행은 미종료로 남아 만료 복구가 정리한다 — 응답을 500 으로 뒤집지 않는다.
        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED);
    }

    @Test
    void 선행_처리를_통과하면_요청_기록_id_를_생성_단계로_넘긴다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);

        AiChatMessageSendService.PreparedChatTurn turn = service.prepare(command);

        // 생성 이후의 요청 종료(성공 확정·실패 기록·예약 반환)가 잠글 행을 가리킨다.
        assertThat(turn.turnRequestId()).isEqualTo(TURN_REQUEST_ID);
    }

    @Test
    void rate_limit_검사가_Allowed_면_통과해서_턴이_정상_진행된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Allowed());
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        assertThatCode(() -> executeTurn(command)).doesNotThrowAnyException();
        verify(userMessageRateLimiter).tryConsume(USER_ID);
    }

    @Test
    void rate_limit_검사가_Denied_면_TooManyRequestsException_을_던지고_아무것도_진행하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Denied());

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED);

        // rate limit 이 첫 관문이므로 이후 협력자는 하나도 호출되지 않아야 한다.
        verifyNoInteractions(persistService, aiChatClient, inputModerationClient,
                userTokenBudgetWriter, userBookRepository, bookRepository);
    }

    @Test
    void 한도_초과_시_RateLimitInfo_에_retryAfter_와_limit_정보가_담긴다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Denied());

        assertThatThrownBy(() -> executeTurn(command))
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
    void rate_limit_검사가_Bypassed_면_검사_없이_통과해서_턴이_정상_진행된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(userMessageRateLimiter.tryConsume(USER_ID))
                .willReturn(new UserMessageRateLimiter.Result.Bypassed());
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        assertThatCode(() -> executeTurn(command)).doesNotThrowAnyException();
    }

    @Test
    void 예산이_소진되면_429_USER_TOKEN_BUDGET_EXCEEDED_와_RetryAfter() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(aiChatTurnRequestWriter.reserveWithRecord(anyLong(), anyLong(), anyInt()))
                .willReturn(new UserTokenBudgetWriter.ReserveResult.Denied(Duration.ofMinutes(90)));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .satisfies(ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiChatErrorCode.USER_TOKEN_BUDGET_EXCEEDED);
                    assertThat(ex.getRateLimitInfo().retryAfter()).isEqualTo(Duration.ofMinutes(90));
                    assertThat(ex.getRateLimitInfo().limitTokens()).isEqualTo(120000L);
                    assertThat(ex.getRateLimitInfo().remainingTokens()).isZero();
                });
        // 예약이 이력 조회·moderation 앞 관문이므로, 거절이면 이후 단계가 하나도 진행되지 않는다.
        verifyNoInteractions(persistService, inputModerationClient, aiChatClient);
    }

    @Test
    void 예산_거절_이후에는_환불을_호출하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(aiChatTurnRequestWriter.reserveWithRecord(anyLong(), anyLong(), anyInt()))
                .willReturn(new UserTokenBudgetWriter.ReserveResult.Denied(Duration.ofMinutes(90)));

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(TooManyRequestsException.class);

        // 예약된 것이 없으므로 되돌릴 대상도 없다 — 종료 트랜잭션이 잠근 행에서 0 을 읽는다.
        verify(userTokenBudgetWriter, never()).refund(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 생성_정상_완료시_실측_사용량과_메시지_입력_추정을_요청_종료_트랜잭션에_넘긴다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문"); // 2자 → 입력 추정 1
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15)); // 실측 출력 5
        givenCommittedSuccess(42L, 10, 5, 15);

        executeTurn(command);

        // 저장·정산·예산 보정·요청 성공 확정은 한 트랜잭션(AiChatTurnOutcomeWriter)이 맡는다.
        // 서비스는 판정 결과와 청구량 A 의 입력 몫(메시지 입력 추정 1)만 넘긴다.
        ArgumentCaptor<AiChatGenerationOutcome> captor = ArgumentCaptor.forClass(AiChatGenerationOutcome.class);
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), captor.capture(), eq(1));
        assertThat(captor.getValue().isSuccess()).isTrue();
        assertThat(captor.getValue().content()).isEqualTo("응답");
        assertThat(captor.getValue().outputTokens()).isEqualTo(5);
        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
    }

    @Test
    void 사용량이_실린_청크가_여러_건이면_더하지_않고_마지막_값을_실측으로_넘긴다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 공급자가 사용량을 두 번 실어 보낸 경우 — 뒤의 값이 그 턴의 실측이다(3 + 5 = 8 이 아니다).
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(
                        AiChatStreamChunk.ofDelta("응답"),
                        AiChatStreamChunk.ofUsage(10, 3, 13),
                        AiChatStreamChunk.ofFinishReason("STOP"),
                        AiChatStreamChunk.ofUsage(10, 5, 15)));
        givenCommittedSuccess(42L, 10, 5, 15);

        executeTurn(command);

        ArgumentCaptor<AiChatGenerationOutcome> captor = ArgumentCaptor.forClass(AiChatGenerationOutcome.class);
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), captor.capture(), anyInt());
        assertThat(captor.getValue().outputTokens()).isEqualTo(5);
        assertThat(captor.getValue().totalTokens()).isEqualTo(15);
    }

    @Test
    void 본문_없이_메타데이터만_실린_청크는_token_이벤트로_내보내지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15)); // 델타 1건 + 종료 사유 청크 + 사용량 청크
        givenCommittedSuccess(42L, 10, 5, 15);

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new MessageStreamEvent.Token("응답"));
        assertThat(events.get(1)).isInstanceOf(MessageStreamEvent.Done.class);
    }

    @Test
    void 생성_에러면_청구_없이_요청을_끝내_예약을_되돌린다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new RuntimeException("boom")));
        givenFinishedWithoutCharge();

        executeTurn(command); // 예외를 던지지 않고 error 이벤트로 바꾼다

        // 예약 반환은 요청 행을 잠그는 종료 트랜잭션이 함께 수행한다(서비스가 직접 환불하지 않는다).
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.STREAM_ERROR.name()));
        verify(userTokenBudgetWriter, never()).refund(anyLong(), anyInt(), anyInt());
    }

    @Test
    void 생성_에러면_게이트_계상을_보상_차감한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new RuntimeException("boom")));
        givenFinishedWithoutCharge();

        executeTurn(command);

        // OpenAI 가 토큰을 소모하지 않았으므로 확보했던 분당 계상을 되돌린다.
        verify(aiChatClient).releaseRateLimitPermit(GATE_PERMIT);
    }

    @Test
    void 게이트_보상_차감이_실패해도_error_이벤트는_그대로_반환된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new RuntimeException("boom")));
        givenFinishedWithoutCharge();
        willThrow(new RuntimeException("redis down"))
                .given(aiChatClient).releaseRateLimitPermit(GATE_PERMIT);

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 성공_확정_커밋이_실패하면_현재_상태를_다시_확인하고_미종료일_때만_청구_없이_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt()))
                .willThrow(new RuntimeException("db down"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        verify(aiChatTurnOutcomeWriter).currentOutcome(TURN_REQUEST_ID);
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
        // 생성이 성공해 OpenAI 가 실제로 토큰을 소모했으므로 분당 계상은 그대로가 맞다.
        verify(aiChatClient, never()).releaseRateLimitPermit(any());
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 커밋_응답이_끊겼어도_이미_성공으로_반영돼_있으면_예약을_되돌리지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt()))
                .willThrow(new RuntimeException("커밋 응답이 끊겼다"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                        AiChatTurnRequest.Status.SUCCEEDED, 42L));

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
        verify(aiChatClient, never()).releaseRateLimitPermit(any());
    }

    @Test
    void 정상_완주면_게이트_계상을_보상_차감하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(1L, 10, 5, 15);

        executeTurn(command);

        verify(aiChatClient, never()).releaseRateLimitPermit(any());
    }

    @Test
    void 입력_가드레일_차단_시_청구_없이_요청을_끝내_예약을_되돌린다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("차단 대상"), any()))
                .willReturn(InputModerationResult.blocked(List.of("self-harm")));

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(BadRequestException.class);

        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT.name());
    }

    @Test
    void Moderation_불능_UNAVAILABLE_시_청구_없이_요청을_끝내_예약을_되돌린다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("질문"), any()))
                .willReturn(InputModerationResult.serviceUnavailable("OpenAI Moderation 502"));

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE.name());
    }

    @Test
    void 게이트_거절_시_예약을_전액_환불하고_예외를_그대로_던진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        willThrow(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST))
                .given(aiChatClient).acquireRateLimitPermit(any(AiChatStreamCommand.class));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(TooManyRequestsException.class))
                .extracting(TooManyRequestsException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_RATE_LIMIT_BURST);

        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.AI_RATE_LIMIT_BURST.name());
        verify(aiChatClient, never()).generateStream(any(AiChatStreamCommand.class));
        // 게이트 거절은 USER 저장 전이어야 한다 — 응답 없는 USER 메시지가 이력에 남지 않는다.
        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        // 거절은 tryAcquire 가 스스로 계상을 되돌렸으므로 여기서 또 보상하면 이중 차감이다.
        verify(aiChatClient, never()).releaseRateLimitPermit(any());
    }

    @Test
    void USER_저장이_실패하면_게이트_계상을_보상_차감하고_예외를_그대로_전파한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        willThrow(new RuntimeException("db down"))
                .given(persistService).recordUserMessage(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        // 생성이 일어나지 않았으므로 게이트 계상 보상 + 청구 없는 요청 종료(예약 반환) 둘 다 수행된다.
        verify(aiChatClient).releaseRateLimitPermit(GATE_PERMIT);
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
        verify(aiChatClient, never()).generateStream(any(AiChatStreamCommand.class));
    }

    @Test
    void 사전_단계에서_NotFoundException_이_던져지면_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(persistService.loadHistory(7L, 1L))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        // 예약 이후의 실패는 명시된 거절 경로(moderation·게이트)가 아니어도 청구 없이 끝난다 — 공통 종료 경로의 계약.
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.SESSION_NOT_FOUND.name());
    }

    @Test
    void 사전_단계에서_BadRequestException_SESSION_LOCKED_도_그대로_전파된다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(persistService.loadHistory(7L, 1L))
                .willThrow(new BadRequestException(AiChatErrorCode.SESSION_LOCKED));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_LOCKED);
    }

    @Test
    void 입력_가드레일에_차단되면_REJECTED_저장_후_BadRequest_를_던지고_생성은_시작하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("차단 대상"), any()))
                .willReturn(InputModerationResult.blocked(List.of("self-harm")));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT);

        verify(persistService).recordRejectedUserMessage(7L, "차단 대상");
        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(aiChatClient, never()).generateStream(any(AiChatStreamCommand.class));
    }

    @Test
    void 입력_가드레일_차단_응답_메시지는_거부_정본_텍스트다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("차단 대상"), any()))
                .willReturn(InputModerationResult.blocked(List.of("sexual-minors")));

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요.");
    }

    @Test
    void Moderation_API_장애_UNAVAILABLE_이면_저장없이_ServiceUnavailable_을_던진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(inputModerationClient.check(eq("질문"), any()))
                .willReturn(InputModerationResult.serviceUnavailable("OpenAI Moderation 502"));

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceUnavailableException.class))
                .extracting(ServiceUnavailableException::getErrorCode)
                .isEqualTo(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);

        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(persistService, never()).recordRejectedUserMessage(anyLong(), anyString());
        verify(aiChatClient, never()).generateStream(any(AiChatStreamCommand.class));
    }

    @Test
    void 생성이_정상이면_Token_과_Done_이벤트가_순서대로_반환되고_USER_와_ASSISTANT_가_저장된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "주제 요약");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("이 책은 자연 앞에서 인간의 한계를 그립니다.", 312, 58, 370));
        givenCommittedSuccess(42L, 312, 58, 370);

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
        // ASSISTANT 저장은 요청 종료 트랜잭션 안에서 일어난다 — 서비스가 따로 저장하지 않는다.
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
    }

    @Test
    void 생성_에러면_부분_본문을_저장하지_않고_Error_이벤트만_내보낸다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.concat(
                        Flux.just(AiChatStreamChunk.ofDelta("받다 만 ")),
                        Flux.error(new RuntimeException("connection reset"))));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new MessageStreamEvent.Token("받다 만 "));
        assertThat(events.get(1))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .extracting(MessageStreamEvent.Error::code)
                .isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());

        // 받은 데까지의 조각은 정상 답변이 아니므로 어떤 형태로도 저장하지 않는다
        // (본문 없이 끝내는 경로 자체의 단언은 AiChatTurnOutcomeWriterTest 가 맡는다).
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
    }

    @Test
    void TooManyRequestsException_BURST_이면_AI_RATE_LIMIT_BURST_코드와_RateLimitInfo_가_Error_이벤트로_운반된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(13), null, null, null, null,
                Duration.ofSeconds(12), null
        );
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new TooManyRequestsException(AiChatErrorCode.AI_RATE_LIMIT_BURST, info)));
        givenFinishedWithoutCharge();

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
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new TooManyRequestsException(AiChatErrorCode.AI_QUOTA_EXHAUSTED)));
        givenFinishedWithoutCharge();

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
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "이번 질문");

        givenLoadHistory(sessionId, List.of(
                new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
        ), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        ArgumentCaptor<AiChatStreamCommand> captor = ArgumentCaptor.forClass(AiChatStreamCommand.class);
        verify(aiChatClient).generateStream(captor.capture());
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
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "이번 질문");

        givenLoadHistory(sessionId, "이전 대화를 압축한 누적 요약", List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        ArgumentCaptor<AiChatStreamCommand> captor = ArgumentCaptor.forClass(AiChatStreamCommand.class);
        verify(aiChatClient).generateStream(captor.capture());
        assertThat(captor.getValue().contextSummary()).isEqualTo("이전 대화를 압축한 누적 요약");
    }

    @Test
    void 생성_타임아웃_등_미분류_IO_예외는_AI_STREAM_INTERRUPTED_error_이벤트가_된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        // RestClient I/O 실패(읽기 타임아웃 포함)는 ResourceAccessException 으로 올라온다.
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new org.springframework.web.client.ResourceAccessException("read timeout")));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        MessageStreamEvent.Error error = (MessageStreamEvent.Error) events.get(0);
        assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_STREAM_INTERRUPTED.name());
    }

    @Test
    void 이미_다른_실행이_끝낸_요청이면_성공을_새로_알리지_않는다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        // 만료 복구 등 다른 실행이 먼저 이 요청을 끝낸 경우.
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt()))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                        AiChatTurnRequest.Status.EXPIRED, null));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
        assertThat(events).noneMatch(event -> event instanceof MessageStreamEvent.Done
                || event instanceof MessageStreamEvent.Replace);
    }

    // ── 진행 목록·전달 분리·기한 ─────────────────────────────────────────

    @Test
    void 후처리까지_끝나면_진행_목록에서_빠진다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(42L, 10, 5, 15);

        executeTurn(command);

        assertThat(aiChatInFlightTurnRegistry.inFlightCount()).isZero();
    }

    @Test
    void 선행_처리가_거절되면_그_호출의_진행_목록_자리도_정리한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "   ");

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(BadRequestException.class);

        assertThat(aiChatInFlightTurnRegistry.inFlightCount()).isZero();
    }

    @Test
    void 중복으로_거절된_재전송도_진행_목록_자리를_남기지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        given(aiChatTurnRequestWriter.claim(anyLong(), anyLong(), anyString(), any(Duration.class)))
                .willThrow(new DataIntegrityViolationException("uk_ai_chat_turn_request_user_request 위반"));

        assertThatThrownBy(() -> executeTurn(command)).isInstanceOf(ConflictException.class);

        assertThat(aiChatInFlightTurnRegistry.inFlightCount()).isZero();
    }

    @Test
    void 종료_절차가_시작되면_새_요청을_503_으로_거절하고_어떤_부수_효과도_시작하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        aiChatInFlightTurnRegistry.blockNewTurns();

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceUnavailableException.class))
                .extracting(ServiceUnavailableException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SERVER_SHUTTING_DOWN);

        verifyNoInteractions(aiChatTurnRequestWriter, persistService, aiChatClient, userMessageRateLimiter);
    }

    @Test
    void 연결이_끊겨_전달_채널이_닫혀도_저장_정산은_끝까지_간다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(42L, 10, 5, 15);

        ChatDeliveryChannel deliveryChannel = ChatDeliveryChannel.open(STREAMING);
        deliveryChannel.close(); // 전달 VT 가 클라이언트 이탈로 이미 끝낸 상태
        executeTurn(command, service, deliveryChannel);

        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), any(), anyInt());
        assertThat(aiChatInFlightTurnRegistry.inFlightCount()).isZero();
    }

    @Test
    void 전달_큐가_포화되면_델타를_버리고_성공_커밋_뒤_완성본_교체로_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 큐 상한이 1 이라 두 번째 델타에서 완성본 대기로 넘어간다(느린 전달 모사).
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(
                        AiChatStreamChunk.ofDelta("앞"),
                        AiChatStreamChunk.ofDelta("뒤"),
                        AiChatStreamChunk.ofFinishReason("STOP"),
                        AiChatStreamChunk.ofUsage(10, 5, 15)));
        givenCommittedSuccess(42L, 10, 5, 15);

        ChatDeliveryChannel deliveryChannel =
                ChatDeliveryChannel.open(new AiChatProperties.Streaming(120, 30, 150, 60, 60, 60, 1));
        executeTurn(command, service, deliveryChannel);
        List<MessageStreamEvent> events = drain(deliveryChannel);

        // 포화 시점에 대기 델타를 버렸으므로 남는 것은 완성본 교체 하나다.
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Replace.class))
                .extracting(MessageStreamEvent.Replace::content)
                .isEqualTo("앞뒤");
    }

    @Test
    void 무응답_기한을_넘기면_기한_초과로_판정해_청구_없이_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 첫 청크 뒤로 아무것도 오지 않는 스트림 — 무응답 기한 1초에 걸린다(전체 기한은 넉넉히 120초).
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.concat(Flux.just(AiChatStreamChunk.ofDelta("첫 조각")), Flux.never()));
        givenFinishedWithoutCharge();

        AiChatMessageSendService shortIdleService =
                serviceWith(propertiesWith(new AiChatProperties.Streaming(120, 1, 150, 60, 60, 60, 256)));
        executeTurn(command, shortIdleService);

        verify(aiChatTurnOutcomeWriter, timeout(3_000)).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.TIMED_OUT.name()));
    }

    @Test
    void 청크가_계속_와도_전체_기한을_넘기면_기한_초과로_판정한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 100ms 마다 조각이 오므로 무응답 기한(30초)에는 걸리지 않는다 — 전체 기한 1초만이 이 스트림을 끊는다.
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.interval(Duration.ofMillis(100))
                        .map(tick -> AiChatStreamChunk.ofDelta("조각")));
        givenFinishedWithoutCharge();

        AiChatMessageSendService shortTotalService =
                serviceWith(propertiesWith(new AiChatProperties.Streaming(1, 30, 150, 60, 60, 60, 256)));
        executeTurn(command, shortTotalService);

        verify(aiChatTurnOutcomeWriter, timeout(3_000)).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.TIMED_OUT.name()));
    }

    @Test
    void 후처리가_전체_기한보다_오래_걸려도_생성_타이머가_저장_정산을_끊지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        // 저장·정산이 전체 기한(1초)보다 오래 걸리는 상황.
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt()))
                .willAnswer(invocation -> {
                    Thread.sleep(1_500);
                    return new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                            new AiChatTurnOutcomeWriter.SavedAssistantMessage(42L, 10, 5, 15, SAVED_AT));
                });

        AiChatMessageSendService shortTotalService =
                serviceWith(propertiesWith(new AiChatProperties.Streaming(1, 30, 150, 60, 60, 60, 256)));
        List<MessageStreamEvent> events = executeTurn(command, shortTotalService);

        // 후처리는 리액티브 체인 밖 VT 에서 돌아 기한의 영향을 받지 않는다 — 성공으로 끝난다.
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Done.class);
        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
    }

    @Test
    void 후처리_제출이_거절되면_저장_정산을_시작하지_않고_진행_목록만_정리한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        willThrow(new RejectedExecutionException("실행기가 닫혔다"))
                .given(aiChatPostProcessingExecutor).execute(any(Runnable.class));

        List<MessageStreamEvent> events = executeTurn(command);

        // 저장·정산은 일어나지 않은 것이다 — 삼켜서 성공으로 기록하지 않는다.
        verifyNoInteractions(aiChatTurnOutcomeWriter);
        // 요청 기록은 미종료(RESERVED)로 남아 예약 복구 대상이 된다 — 이 실행이 실패로 끝내지 않는다.
        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
        assertThat(aiChatInFlightTurnRegistry.inFlightCount()).isZero();
        assertThat(events.getLast())
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .extracting(MessageStreamEvent.Error::code)
                .isEqualTo(AiChatErrorCode.SERVER_SHUTTING_DOWN.name());
    }

    @Test
    void 스트림은_끝났는데_종료_사유가_없으면_청구하지_않고_error_로_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 로컬 입력 검사에 걸려 거부 문구 한 조각만 흘러온 모양 — 종료 사유도 사용량도 없다.
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(AiChatStreamChunk.ofDelta("요청을 처리할 수 없습니다.")));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        // 클라이언트가 보는 것: 거부 문구 조각(token) 뒤에 error. 저장도 청구도 없다.
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).isEqualTo(new MessageStreamEvent.Token("요청을 처리할 수 없습니다."));
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.NO_FINISH_REASON.name()));
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any());
    }
}
