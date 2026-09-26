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
import com.readum.domain.aiChat.out.CompletedTurnStore;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.time.InstantSource;
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
    private static final LocalDateTime SAVED_AT = LocalDateTime.of(2026, 9, 6, 12, 0, 0);
    /** 기한 넷과 큐 상한 — 운영 후보값 그대로. 기한을 짧게 둬야 하는 테스트는 따로 서비스를 만든다. */
    /** 진행 중 턴 상한 — 운영 값. 상한 거절을 보는 테스트는 상한 1짜리 진행 목록을 따로 만든다. */
    private static final int MAX_IN_FLIGHT_TURNS = 300;
    private static final AiChatProperties.Streaming STREAMING =
            new AiChatProperties.Streaming(120, 30, 150, 60, 60, 60, 256, 300);

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
    private com.readum.domain.aiChat.out.AiAvailability aiAvailability;

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

    /** 완성 답변 보관소 대역 — 무엇이 몇 번 적혔는지 그대로 들여다볼 수 있게 메모리에 담는다. */
    private final RecordingCompletedTurnStore completedTurnStore = new RecordingCompletedTurnStore();

    // 진행 목록은 순수 메모리 상태라 진짜를 쓴다 — 등록·정리가 실제로 맞물리는지 보려면 대역이 도움이 되지 않는다.
    private final AiChatInFlightTurnRegistry aiChatInFlightTurnRegistry = new AiChatInFlightTurnRegistry(MAX_IN_FLIGHT_TURNS);

    // 재시도 규칙도 순수 계산이라 진짜를 쓴다. 대기만 즉시 끝나게 바꿔 테스트가 초 단위로 멈추지 않게 한다 —
    // 멈추는 조건 자체는 AiChatPostProcessingRetryTest 가 따로 확인한다.
    private final AiChatPostProcessingRetry aiChatPostProcessingRetry = new AiChatPostProcessingRetry(
            STREAMING.turnRequestExpiryTimeout(), InstantSource.system(), interval -> true);

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
        // 후처리 실행기 기본값: 제출받은 작업을 그 자리에서 실행. 거절 케이스는 각 테스트에서 override.
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(aiChatPostProcessingExecutor).execute(any(Runnable.class));
        service = serviceWith(aiChatProperties);
    }

    /**
     * 완성 답변 보관소는 여기서 <b>실제 동작하는 대역</b>을 쓴다 — "언제 한 번 적히고 언제 지워지는가" 가
     * 이 서비스의 계약이라, 통째로 꺼 버리면 그 시점을 보지 못한다.
     */
    private AiChatMessageSendService serviceWith(AiChatProperties properties) {
        return serviceWith(properties, aiChatInFlightTurnRegistry);
    }

    private AiChatMessageSendService serviceWith(
            AiChatProperties properties, AiChatInFlightTurnRegistry inFlightTurnRegistry) {
        return new AiChatMessageSendService(
                persistService, aiChatClient, properties,
                userMessageRateLimiter, userBookRepository, bookRepository, inputModerationClient,
                aiAvailability, aiChatTurnRequestWriter, aiChatTurnOutcomeWriter,
                inFlightTurnRegistry, aiChatPostProcessingRetry, aiChatPostProcessingExecutor,
                tokenCounter, completedTurnStore
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
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
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
    void 공급자가_막혀_있으면_예약도_외부_호출도_시작하지_않고_503_으로_거절한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        org.mockito.BDDMockito.willThrow(
                        new com.readum.domain.aiChat.exception.AiDependencyUnavailableException(
                                AiChatErrorCode.AI_PROVIDER_UNAVAILABLE))
                .given(aiAvailability).requireAvailable(
                        com.readum.domain.aiChat.out.AiAvailability.Capability.CHAT);

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceUnavailableException.class))
                .extracting(ex -> ((com.readum.domain.exception.BusinessException) ex).getErrorCode())
                .isEqualTo(AiChatErrorCode.AI_PROVIDER_UNAVAILABLE);

        // 확인이 예약보다 앞서야 하는 이유: 쓰지도 못할 예약을 잡았다가 되돌리기 전에 프로세스가 죽으면
        // 사용자의 하루 예산만 깎인다. 외부 검토 호출도 시작하지 않는다(응답 모델이 막혔으면 그 판정은 쓸 데가 없다).
        verify(aiChatTurnRequestWriter, never()).reserveWithRecord(anyLong(), anyLong(), anyInt());
        verifyNoInteractions(inputModerationClient, aiChatClient, persistService);
        // 잡아 둔 요청 자리는 청구 없이 닫는다 — 미종료로 남겨 두면 예약 반환 대상이 되어 떠돈다.
        verify(aiChatTurnOutcomeWriter)
                .finishWithoutCharge(anyLong(), eq(AiChatTurnRequest.Status.FAILED), anyString());
    }

    @Test
    void 공급자_가용_확인은_사용자_폭주_가드_뒤_예산_예약_앞에서_일어난다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        InOrder admissionOrder = inOrder(userMessageRateLimiter, aiAvailability, aiChatTurnRequestWriter);
        admissionOrder.verify(userMessageRateLimiter).tryConsume(USER_ID);
        admissionOrder.verify(aiAvailability)
                .requireAvailable(com.readum.domain.aiChat.out.AiAvailability.Capability.CHAT);
        admissionOrder.verify(aiChatTurnRequestWriter).reserveWithRecord(anyLong(), anyLong(), anyInt());
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
    void 진행_중_턴_상한에_닿으면_요청_기록도_예약도_시작하지_않고_503_으로_거절한다() {
        // 상한 1짜리 진행 목록에 이미 한 턴이 올라가 있는 상태를 만든다.
        AiChatInFlightTurnRegistry fullRegistry = new AiChatInFlightTurnRegistry(1);
        fullRegistry.register("먼저-온-요청", 7L, 99L);
        AiChatMessageSendService serviceAtCapacity = serviceWith(aiChatProperties, fullRegistry);
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");

        assertThatThrownBy(() -> executeTurn(command, serviceAtCapacity))
                .asInstanceOf(InstanceOfAssertFactories.type(ServiceUnavailableException.class))
                .extracting(ServiceUnavailableException::getErrorCode)
                .isEqualTo(AiChatErrorCode.AI_CHAT_CAPACITY_EXCEEDED);

        // 거절이 첫 부수 효과보다 앞이라 남는 자국이 하나도 없다 — 요청 자리도, 예약도, 외부 호출도 없다.
        verifyNoInteractions(aiChatTurnRequestWriter);
        verifyNoInteractions(aiChatTurnOutcomeWriter);
        verifyNoInteractions(userMessageRateLimiter);
        verifyNoInteractions(inputModerationClient);
        verifyNoInteractions(persistService);
        // 진행 목록도 원래대로다 — 거절된 턴은 자리를 잡지 않았고, 먼저 온 턴은 그대로 남아 있다.
        assertThat(fullRegistry.inFlightCount()).isEqualTo(1);
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
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), captor.capture(), eq(1), any(LocalDateTime.class));
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
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), captor.capture(), anyInt(), any(LocalDateTime.class));
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
    void 성공_확정_커밋이_실패하고_되살릴_기록도_없으면_현재_상태를_다시_확인하고_청구_없이_끝낸다() {
        // 되살릴 근거가 있을 때의 처리는 따로 본다 — 그때는 환불하지 않고 되살리기에 맡긴다.
        completedTurnStore.saveFails = true;
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new RuntimeException("db down"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        verify(aiChatTurnOutcomeWriter).currentOutcome(TURN_REQUEST_ID);
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 커밋_응답이_끊겼어도_이미_성공으로_반영돼_있으면_예약을_되돌리지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new RuntimeException("커밋 응답이 끊겼다"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished(
                        AiChatTurnRequest.Status.SUCCEEDED, 42L));

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
    }

    @Test
    void 저장이_일시_실패해도_다시_시도해_확정하면_사용자는_성공으로_끝난다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        // 풀 순간 고갈·잠금 경합처럼 몇 초 안에 지나가는 실패 — 두 번째 시도에서 확정된다.
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new CannotAcquireLockException("Lock wait timeout exceeded"))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                        new AiChatTurnOutcomeWriter.SavedAssistantMessage(42L, 10, 5, 15, SAVED_AT)));

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Done.class);
        verify(aiChatTurnOutcomeWriter, times(2))
                .finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class));
        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
        assertThat(aiChatPostProcessingRetry.retryCount()).isEqualTo(1);
        assertThat(aiChatPostProcessingRetry.failureCount(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isZero();
    }

    @Test
    void 저장이_데이터_오류로_실패하면_다시_시도하지_않고_기존_실패_경로로_간다() {
        completedTurnStore.saveFails = true;
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new DataIntegrityViolationException("제약 위반"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        // 다시 시도해도 같은 결과라 한 번만 부르고 곧바로 기존 재확인 경로로 간다.
        verify(aiChatTurnOutcomeWriter)
                .finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class));
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
        assertThat(aiChatPostProcessingRetry.retryCount()).isZero();
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 저장이_세_번_다_실패하면_기존_재확인_경로로_가고_끝내_실패로_센다() {
        completedTurnStore.saveFails = true;
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        verify(aiChatTurnOutcomeWriter, times(3))
                .finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class));
        verify(aiChatTurnOutcomeWriter).currentOutcome(TURN_REQUEST_ID);
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
        assertThat(aiChatPostProcessingRetry.retryCount()).isEqualTo(2);
        assertThat(aiChatPostProcessingRetry.failureCount(
                AiChatPostProcessingRetry.FinishKind.SUCCESS_COMMIT)).isEqualTo(1);
        // 완성본을 보관하지 않으므로 답변은 저장되지 않고 사용자는 error 로 끝난다.
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 정상_완주면_게이트_계상을_보상_차감하지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(1L, 10, 5, 15);

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
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
    void USER_저장이_실패하면_청구_없이_요청을_끝내고_예외를_그대로_전파한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        willThrow(new RuntimeException("db down"))
                .given(persistService).recordUserMessage(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> executeTurn(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        // 생성이 일어나지 않았으므로 청구 없는 요청 종료(예약 반환)를 수행한다.
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
    void 로컬_입력_검사에_걸리면_moderation_을_부르지_않고_moderation_차단과_같은_모양으로_거절한다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "차단 대상");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.isBlockedByLocalInputCheck(any(AiChatStreamCommand.class))).willReturn(true);

        assertThatThrownBy(() -> executeTurn(command))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT);

        // 처리는 moderation 차단과 같다: REJECTED 기록 + 400, USER 미저장, 생성 미시작.
        verify(persistService).recordRejectedUserMessage(7L, "차단 대상");
        verify(persistService, never()).recordUserMessage(anyLong(), anyLong(), anyString());
        verify(aiChatClient, never()).generateStream(any(AiChatStreamCommand.class));
        // 로컬 검사는 공짜지만 moderation 은 공급자 RPM 자원을 쓴다 — 거절될 요청이 그것을 소모하지 않는다.
        verifyNoInteractions(inputModerationClient);
        // 예약 반환은 공통 종료 경로가 한 트랜잭션으로 한다.
        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                TURN_REQUEST_ID, AiChatTurnRequest.Status.FAILED,
                AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT.name());
    }

    @Test
    void 로컬_입력_검사를_통과하면_그_다음에_외부_moderation_을_부른다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 1, 1, 2));
        givenCommittedSuccess(1L, 1, 1, 2);

        executeTurn(command);

        InOrder inputCheckOrder = inOrder(aiChatClient, inputModerationClient);
        inputCheckOrder.verify(aiChatClient).isBlockedByLocalInputCheck(any(AiChatStreamCommand.class));
        inputCheckOrder.verify(inputModerationClient).check(eq("질문"), any());
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
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any(), any(LocalDateTime.class));
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
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any(), any(LocalDateTime.class));
    }

    @Test
    void 공급자가_한도_초과로_거절하면_그_코드와_RateLimitInfo_가_Error_이벤트로_운반된다() {
        Long sessionId = 7L;
        SendMessageCommand command = new SendMessageCommand(USER_ID, sessionId, REQUEST_ID, "질문");

        givenLoadHistory(sessionId, List.of(), 100L);
        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(13), null, null, null, null,
                Duration.ofSeconds(12), null
        );
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(
                        new TooManyRequestsException(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED, info)));
        givenFinishedWithoutCharge();

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Error.class))
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED.name());
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
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
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

        verify(aiChatTurnOutcomeWriter).finishSuccessfully(eq(TURN_REQUEST_ID), any(), anyInt(), any(LocalDateTime.class));
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
                ChatDeliveryChannel.open(new AiChatProperties.Streaming(120, 30, 150, 60, 60, 60, 1, 300));
        executeTurn(command, service, deliveryChannel);
        List<MessageStreamEvent> events = drain(deliveryChannel);

        // 포화 시점에 대기 델타를 버렸으므로 남는 것은 완성본 교체 하나다.
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .asInstanceOf(InstanceOfAssertFactories.type(MessageStreamEvent.Replace.class))
                .extracting(MessageStreamEvent.Replace::content)
                .isEqualTo("앞뒤");
    }

    /**
     * 기한 초과 판정은 이 계층의 책임으로 남아 있다. 다만 <b>기한을 재는 일</b>은 어댑터가 공급자 상태 보호 구간
     * 안쪽에서 한다({@code ProtectedChatModel}) — 바깥에 걸면 기한 초과가 그 구간에는 취소로만 보여
     * "공급자가 응답을 끊었다" 와 "우리가 구독을 놓았다" 가 구분되지 않기 때문이다.
     * 그래서 여기서는 기한이 실제로 흐르기를 기다리는 대신, <b>포트가 기한 초과를 오류 신호로 올려보낸 상황</b>을
     * 그대로 만들어 판정과 정산을 확인한다. 타이머가 제때 발동하는지는 어댑터 쪽 테스트가 맡는다.
     */
    @Test
    void 포트가_기한_초과를_알리면_기한_초과로_판정해_청구_없이_끝낸다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        // 조각이 좀 오다가 기한 초과로 끊긴 스트림 — 어댑터가 건 무응답/전체 기한이 이 모양으로 도착한다.
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(AiChatStreamChunk.ofDelta("첫 조각"))
                        .concatWith(Flux.error(new TimeoutException("무응답 기한 초과"))));
        givenFinishedWithoutCharge();

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter, timeout(3_000)).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.TIMED_OUT.name()));
    }

    @Test
    void 기한_초과와_그_밖의_스트림_오류를_다르게_판정한다() {
        // 사후 확인에서 "왜 끊겼는가" 가 남아야 하므로 둘을 같은 실패로 뭉뚱그리지 않는다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new java.io.IOException("연결이 끊겼다")));
        givenFinishedWithoutCharge();

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter, timeout(3_000)).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED),
                eq(AiChatGenerationOutcome.Status.STREAM_ERROR.name()));
    }

    @Test
    void 후처리가_전체_기한보다_오래_걸려도_생성_타이머가_저장_정산을_끊지_않는다() {
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        // 저장·정산이 전체 기한(1초)보다 오래 걸리는 상황.
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willAnswer(invocation -> {
                    Thread.sleep(1_500);
                    return new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                            new AiChatTurnOutcomeWriter.SavedAssistantMessage(42L, 10, 5, 15, SAVED_AT));
                });

        AiChatMessageSendService shortTotalService =
                serviceWith(propertiesWith(new AiChatProperties.Streaming(1, 30, 150, 60, 60, 60, 256, 300)));
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
        verify(persistService, never()).saveAssistantSuccess(anyLong(), anyString(), any(), any(LocalDateTime.class));
    }

    // --- 완성 답변 보관과 되살리기 --------------------------------------------------------------------

    @Test
    void 생성_중에는_아무것도_적지_않고_끝난_뒤_한_번만_적는다() {
        // 조각은 기록을 거치지 않고 곧바로 전달된다. 되살리기의 근거는 완성본 하나뿐이다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(
                        AiChatStreamChunk.ofDelta("안녕"),
                        AiChatStreamChunk.ofDelta("하세요"),
                        AiChatStreamChunk.ofFinishReason("STOP"),
                        AiChatStreamChunk.ofUsage(10, 5, 15)));
        givenCommittedSuccess(1L, 10, 5, 15);

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events).first().isEqualTo(new MessageStreamEvent.Token("안녕"));
        assertThat(events).element(1).isEqualTo(new MessageStreamEvent.Token("하세요"));
        assertThat(completedTurnStore.saveCount.get())
                .as("조각마다 적지 않는다 — 완성본 한 번뿐이다")
                .isEqualTo(1);
        assertThat(completedTurnStore.saved.get().content()).isEqualTo("안녕하세요");
    }

    @Test
    void 완성본은_DB_확정보다_먼저_보관소에_적힌다() {
        // 이 순서가 "여기서 프로세스가 죽어도 되살릴 수 있다" 를 만든다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willAnswer(invocation -> {
                    assertThat(completedTurnStore.saved.get())
                            .as("DB 확정 트랜잭션이 열릴 때 이미 완성본이 적혀 있어야 한다")
                            .isNotNull();
                    return new AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded(
                            new AiChatTurnOutcomeWriter.SavedAssistantMessage(
                                    1L, 10, 5, 15, LocalDateTime.now()));
                });

        executeTurn(command);

        assertThat(completedTurnStore.saved.get().content()).isEqualTo("응답");
        assertThat(completedTurnStore.saved.get().outputTokens()).isEqualTo(5);
        assertThat(completedTurnStore.deleted.get())
                .as("DB 종료가 확실해진 뒤에 지운다").isTrue();
    }

    @Test
    void 적는_값은_되살리는_쪽이_정상_경로와_같게_확정할_수_있는_전부다() {
        // 본문·실측 사용량만으로는 부족하다 — 청구의 입력 몫(예약 때 센 값), 작성 시각, 요청 신원이 함께 있어야
        // 되살려 확정한 결과가 제때 확정한 결과와 같아진다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(1L, 10, 5, 15);

        executeTurn(command);

        ArgumentCaptor<LocalDateTime> generatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> estimatedInputTokens = ArgumentCaptor.forClass(Integer.class);
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(
                eq(TURN_REQUEST_ID), any(AiChatGenerationOutcome.class),
                estimatedInputTokens.capture(), generatedAt.capture());

        CompletedTurnStore.CompletedTurn saved = completedTurnStore.saved.get();
        assertThat(completedTurnStore.savedKey.get())
                .as("열쇠는 요청 기록의 id 다").isEqualTo(TURN_REQUEST_ID);
        assertThat(saved.turnRequestId())
                .as("기록 안에도 요청 신원이 있어야 남의 기록을 되살리지 않는다").isEqualTo(TURN_REQUEST_ID);
        assertThat(saved.sessionId()).isEqualTo(7L);
        assertThat(saved.userId()).isEqualTo(USER_ID);
        assertThat(saved.estimatedInputTokens()).isEqualTo(estimatedInputTokens.getValue());
        assertThat(saved.generatedAt()).isEqualTo(generatedAt.getValue());
        assertThat(saved.finishReason()).isEqualTo("STOP");
        assertThat(saved.inputTokens()).isEqualTo(10);
        assertThat(saved.totalTokens()).isEqualTo(15);
    }

    @Test
    void 보관소가_대답하지_않아도_대화는_그대로_끝난다() {
        // 보관소는 대화를 돕는 장치이지 대화의 조건이 아니다 — 되살리기 보장만 없어진다.
        completedTurnStore.goDown();
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(1L, 10, 5, 15);

        List<MessageStreamEvent> events = executeTurn(command);

        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Done.class);
        verify(aiChatTurnOutcomeWriter).finishSuccessfully(
                anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void 실패한_턴은_보관소에_아무것도_적지_않는다() {
        // 부분 답변은 적히지 않는다 — 되살릴 것이 없으니 지울 것도 없다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.error(new RuntimeException("boom")));
        givenFinishedWithoutCharge();

        executeTurn(command);

        assertThat(completedTurnStore.saveCount.get()).isZero();
        assertThat(completedTurnStore.saved.get()).isNull();
    }

    @Test
    void 확정_커밋이_끊겼는데_기록이_남아_있으면_환불하지_않고_되살리기에_맡긴다() {
        // 여기서 청구 없이 끝내면, 끝까지 만들어져 보관소에 남아 있는 답변을 우리 손으로 버리는 셈이다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new RuntimeException("db down"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));

        List<MessageStreamEvent> events = executeTurn(command);

        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
        assertThat(completedTurnStore.deleted.get())
                .as("되살릴 근거를 지우면 안 된다").isFalse();
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 확정_커밋이_끊겼고_기록도_없으면_기존대로_청구_없이_끝낸다() {
        completedTurnStore.saveFails = true;
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new RuntimeException("db down"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));
        givenFinishedWithoutCharge();

        executeTurn(command);

        verify(aiChatTurnOutcomeWriter).finishWithoutCharge(
                eq(TURN_REQUEST_ID), eq(AiChatTurnRequest.Status.FAILED), anyString());
    }

    @Test
    void 적기에_실패했어도_기록이_남아_있을_수_있으면_환불하지_않고_미룬다() {
        // 적기의 실패는 "적히지 않았다" 가 아니다 — 명령은 닿았는데 응답만 끊겼을 수 있다.
        // 확실히 없다고 읽히기 전에는 예약을 되돌리지 않는다.
        completedTurnStore.goDown();
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        given(aiChatTurnOutcomeWriter.finishSuccessfully(anyLong(), any(AiChatGenerationOutcome.class), anyInt(), any(LocalDateTime.class)))
                .willThrow(new RuntimeException("db down"));
        given(aiChatTurnOutcomeWriter.currentOutcome(TURN_REQUEST_ID))
                .willReturn(new AiChatTurnOutcomeWriter.TurnOutcomeResult.StillRunning(
                        AiChatTurnRequest.Status.RESERVED));

        List<MessageStreamEvent> events = executeTurn(command);

        verify(aiChatTurnOutcomeWriter, never())
                .finishWithoutCharge(anyLong(), any(AiChatTurnRequest.Status.class), anyString());
        assertThat(completedTurnStore.deleted.get())
                .as("판단을 미룬 요청의 근거를 지우면 안 된다").isFalse();
        assertThat(events.getLast()).isInstanceOf(MessageStreamEvent.Error.class);
    }

    @Test
    void 연결이_끊겨도_완성본은_그대로_적힌다() {
        // 기록 대상은 서버가 만든 결과이지 전달된 것이 아니다 — 끊긴 연결이 기록을 멈출 이유가 없다.
        SendMessageCommand command = new SendMessageCommand(USER_ID, 7L, REQUEST_ID, "질문");
        givenLoadHistory(7L, List.of(), 100L);
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(streamOf("응답", 10, 5, 15));
        givenCommittedSuccess(1L, 10, 5, 15);
        ChatDeliveryChannel deliveryChannel = ChatDeliveryChannel.open(STREAMING);
        deliveryChannel.close();

        executeTurn(command, service, deliveryChannel);

        assertThat(completedTurnStore.saved.get()).isNotNull();
        assertThat(completedTurnStore.saved.get().content()).isEqualTo("응답");
    }

    /**
     * 완성 답변 보관소 대역 — 실제 Redis 없이 <b>무엇이 몇 번 적혔고 지워졌는지</b>를 그대로 보관한다.
     * 먼저 적힌 기록을 덮지 않는 성질까지 흉내 내, 이 서비스가 기록을 한 번만 남기는지 단언할 수 있게 한다.
     */
    private static final class RecordingCompletedTurnStore implements CompletedTurnStore {

        private final java.util.concurrent.atomic.AtomicReference<CompletedTurn> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicLong savedKey =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger saveCount =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean deleted =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 참이면 적기가 실패한다 — 적히지 않았을 수도, 응답만 끊겼을 수도 있는 상황. */
        private volatile boolean saveFails;

        /** 참이면 읽기가 알 수 없음으로 답한다 — 보관소가 대답하지 않는 상황. */
        private volatile boolean findFails;

        /** 쓰기도 읽기도 안 되는 상황 — 보관소가 통째로 죽었다. */
        private void goDown() {
            saveFails = true;
            findFails = true;
        }

        @Override public boolean save(long turnRequestId, CompletedTurn completedTurn) {
            if (saveFails) {
                return false;
            }
            saveCount.incrementAndGet();
            savedKey.set(turnRequestId);
            // 먼저 적힌 기록은 덮지 않는다(어댑터의 NX 와 같은 성질).
            saved.compareAndSet(null, completedTurn);
            return true;
        }

        @Override public Lookup find(long turnRequestId) {
            if (findFails) {
                return new Lookup.Unknown();
            }
            CompletedTurn recorded = saved.get();
            return (recorded == null) ? new Lookup.Absent() : new Lookup.Found(recorded);
        }

        @Override public void delete(long turnRequestId) {
            deleted.set(true);
        }
    }
}
