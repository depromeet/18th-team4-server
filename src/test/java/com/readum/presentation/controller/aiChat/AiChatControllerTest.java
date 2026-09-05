package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.AiChatSessionDisplayStatus;
import com.readum.domain.aiChat.dto.AiChatSessionListResult;
import com.readum.domain.aiChat.dto.BookChatSessionsResult;
import com.readum.domain.aiChat.dto.AiChatSessionResult;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.MessageResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.dto.SendMessageCommand;
import com.readum.domain.aiChat.dto.SummaryDraftCommand;
import com.readum.domain.aiChat.dto.SummaryDraftEligibility.IneligibleReason;
import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.service.AiChatMessageSearchService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiChatSessionSearchService;
import com.readum.domain.aiChat.service.BookChatSessionSearchService;
import com.readum.domain.aiChat.service.SummaryDraftSearchService;
import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.domain.aiChat.service.SummaryEditService;
import com.readum.domain.summary.service.SummarySearchService;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.ServiceUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.domain.exception.UnprocessableEntityException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.common.security.AuthenticatedUserIdArgumentResolver;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Mock
    private AiChatSessionSearchService aiChatSessionSearchService;

    @Mock
    private AiChatMessageSendService aiChatMessageSendService;

    @Mock
    private AiChatMessageSearchService aiChatMessageSearchService;

    @Mock
    private SummaryDraftService summaryDraftService;

    @Mock
    private SummaryEditService summaryEditService;

    @Mock
    private SummarySearchService summarySearchService;

    @Mock
    private SummaryDraftSearchService summaryDraftSearchService;

    @Mock
    private BookChatSessionSearchService bookChatSessionSearchService;

    private MockMvc mockMvc;
    private AiChatController controller;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @BeforeEach
    void setUp() {
        controller = new AiChatController(
                aiChatSessionCreateService,
                aiChatSessionSearchService,
                aiChatMessageSendService,
                aiChatMessageSearchService,
                summaryDraftService,
                summaryEditService,
                summarySearchService,
                summaryDraftSearchService,
                bookChatSessionSearchService,
                new MessageStreamSseSerializer(objectMapper)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedUserIdArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 세션 생성 ──────────────────────────────────────────────────────

    @Test
    void 세션_생성_정상_요청시_201과_세션_id_를_반환한다() throws Exception {
        given(aiChatSessionCreateService.execute(any())).willReturn(new AiChatSessionCreateResult(42L));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(10L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(42));
    }

    @Test
    void 세션_생성_userBookId_누락시_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("userBookId는 필수")));
    }

    @Test
    void 세션_생성_userBookId_가_음수면_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(-1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("양수")));
    }

    @Test
    void 세션_생성_소유권_없는_userBookId_는_404() throws Exception {
        given(aiChatSessionCreateService.execute(any()))
                .willThrow(new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        mockMvc.perform(post("/api/v1/ai-chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AiChatSessionCreateRequest(999_999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("등록된 도서가 아닙니다."));
    }

    // ── 세션 목록 조회 ──────────────────────────────────────────────────────

    @Test
    void 세션_목록_조회_정상_응답() throws Exception {
        LocalDate today = LocalDate.of(2026, 5, 7);
        LocalDate yesterday = today.minusDays(1);
        given(aiChatSessionSearchService.findByUserBookId(any()))
                .willReturn(new AiChatSessionListResult(
                        List.of(
                                new AiChatSessionResult(4L, "최근", AiChatSessionDisplayStatus.SUMMARIZING, today),
                                new AiChatSessionResult(3L, "활성", AiChatSessionDisplayStatus.ACTIVE, today),
                                new AiChatSessionResult(2L, "감상문 완료", AiChatSessionDisplayStatus.SUMMARIZED, yesterday)
                        ),
                        1,
                        20,
                        false
                ));

        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("userBookId", "100")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions.length()").value(3))
                .andExpect(jsonPath("$.data.sessions[0].sessionId").value(4))
                .andExpect(jsonPath("$.data.sessions[0].title").value("최근"))
                .andExpect(jsonPath("$.data.sessions[0].status").value("SUMMARIZING"))
                // lastChattedDate 는 LocalDate 직렬화로 yyyy-MM-dd 만 노출 (timestamp/시간 정보 X)
                .andExpect(jsonPath("$.data.sessions[0].lastChattedDate").value("2026-05-07"))
                .andExpect(jsonPath("$.data.sessions[2].lastChattedDate").value("2026-05-06"))
                .andExpect(jsonPath("$.data.sessions[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.sessions[2].status").value("SUMMARIZED"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 세션_목록_조회_세션이_없으면_빈_배열을_반환한다() throws Exception {
        given(aiChatSessionSearchService.findByUserBookId(any()))
                .willReturn(new AiChatSessionListResult(List.of(), 1, 20, false));

        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("userBookId", "100")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 세션_목록_조회_userBookId_누락시_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("userBookId는 필수")));
    }

    // ── 책별 대화 세션 목록 조회 ──────────────────────────────────────────────

    @Test
    void 책별_세션_목록_조회_정상_응답() throws Exception {
        given(bookChatSessionSearchService.findByUserBook(eq(100L), eq(USER_ID)))
                .willReturn(new BookChatSessionsResult(
                        new BookChatSessionsResult.BookInfo("데미안", 2020, "민음사", "http://img/x.jpg"),
                        List.of(
                                new BookChatSessionsResult.SessionItem(2L, "세션 제목 A", "최신 본문", LocalDate.of(2026, 5, 7)),
                                new BookChatSessionsResult.SessionItem(1L, "세션 제목 B", null, LocalDate.of(2026, 5, 5))
                        )
                ));

        mockMvc.perform(get("/api/v1/ai-chat/books/100/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.book.title").value("데미안"))
                .andExpect(jsonPath("$.data.book.publishedYear").value(2020))
                .andExpect(jsonPath("$.data.book.publisher").value("민음사"))
                .andExpect(jsonPath("$.data.book.coverImageUrl").value("http://img/x.jpg"))
                .andExpect(jsonPath("$.data.sessions.length()").value(2))
                .andExpect(jsonPath("$.data.sessions[0].sessionId").value(2))
                .andExpect(jsonPath("$.data.sessions[0].sessionTitle").value("세션 제목 A"))
                .andExpect(jsonPath("$.data.sessions[0].latestSummaryContent").value("최신 본문"))
                .andExpect(jsonPath("$.data.sessions[0].lastChattedDate").value("2026-05-07"))
                .andExpect(jsonPath("$.data.sessions[1].sessionId").value(1))
                .andExpect(jsonPath("$.data.sessions[1].sessionTitle").value("세션 제목 B"))
                .andExpect(jsonPath("$.data.sessions[1].lastChattedDate").value("2026-05-05"));
    }

    @Test
    void 책별_세션_목록_조회_본인_책이_아니면_404() throws Exception {
        given(bookChatSessionSearchService.findByUserBook(eq(100L), eq(USER_ID)))
                .willThrow(new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-chat/books/100/sessions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("등록된 도서가 아닙니다."));
    }

    @Test
    void 세션_목록_조회_userBookId_가_음수면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("userBookId", "-1")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("양수")));
    }

    @Test
    void 세션_목록_조회_size_가_101이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("userBookId", "100")
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("size는 100 이하")));
    }

    @Test
    void 세션_목록_조회_소유권_없는_userBookId_는_404() throws Exception {
        given(aiChatSessionSearchService.findByUserBookId(any()))
                .willThrow(new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-chat/sessions")
                        .param("userBookId", "999999")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("등록된 도서가 아닙니다."));
    }

    // ── 메시지 전송 ──────────────────────────────────────────────────────

    @Test
    void 메시지_전송_본문_누락시_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("메시지 본문은 비어 있을 수 없습니다.")));
    }

    @Test
    void 메시지_전송_빈_본문이면_400() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 메시지_전송_whitespace_본문이면_400_변환된다() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("   "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 메시지_전송_4001자_본문이면_400() throws Exception {
        String tooLong = "가".repeat(4001);
        mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest(tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("4000자")));
    }

    // 선행 처리 거절은 요청 스레드에서 동기 예외로 끝나므로, SSE 가 시작되지 않고
    // GlobalExceptionHandler 의 4xx/5xx JSON 이 그대로 동기 응답된다.
    // PreparedChatTurn 은 도메인 패키지 밖에서 만들 수 없어(예약 결과 타입이 package-private),
    // 통과 경로에서는 prepare 의 기본 반환값(null)을 그대로 생성 단계 스텁으로 넘긴다.

    private ResultActions send(String content) throws Exception {
        return mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SendMessageRequest(content))));
    }

    private MvcResult sendAndStartAsync(String content) throws Exception {
        return mockMvc.perform(post("/api/v1/ai-chat/sessions/7/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest(content))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void 메시지_전송_세션_없으면_SSE_를_시작하지_않고_404_JSON_을_응답한다() throws Exception {
        given(aiChatMessageSendService.prepare(any(SendMessageCommand.class)))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        send("질문")
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("세션을 찾을 수 없습니다."));
    }

    @Test
    void 메시지_전송_잠긴_세션이면_400() throws Exception {
        given(aiChatMessageSendService.prepare(any(SendMessageCommand.class)))
                .willThrow(new BadRequestException(AiChatErrorCode.SESSION_LOCKED));

        send("질문")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("감상문 생성 중에는 메시지를 보낼 수 없습니다."));
    }

    @Test
    void 메시지_전송_사용자_호출_한도를_넘으면_429_와_RetryAfter_헤더() throws Exception {
        RateLimitInfo info = new RateLimitInfo(
                Duration.ofSeconds(10), 5L, null, 0L, null, null, null);
        given(aiChatMessageSendService.prepare(any(SendMessageCommand.class)))
                .willThrow(new TooManyRequestsException(AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED, info));

        send("질문")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void 메시지_전송_입력_검사_불능이면_503() throws Exception {
        given(aiChatMessageSendService.prepare(any(SendMessageCommand.class)))
                .willThrow(new ServiceUnavailableException(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE));

        send("질문")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 선행_처리가_거절하면_생성_단계는_시작되지_않는다() throws Exception {
        given(aiChatMessageSendService.prepare(any(SendMessageCommand.class)))
                .willThrow(new BadRequestException(AiChatErrorCode.GUARDRAIL_BLOCKED_INPUT));

        send("차단 대상").andExpect(status().isBadRequest());

        verify(aiChatMessageSendService, never()).generateAndPersistStream(any());
    }

    @Test
    void 메시지_전송_정상_스트림이면_token_과_done_이벤트가_방출된다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 2, 14, 33, 21);
        given(aiChatMessageSendService.generateAndPersistStream(any())).willReturn(Flux.just(
                new MessageStreamEvent.Token("alpha"),
                new MessageStreamEvent.Token(" beta"),
                new MessageStreamEvent.Done(
                        new MessageStreamEvent.TokenCount(312, 58, 370),
                        createdAt)
        ));

        String body = mockMvc.perform(asyncDispatch(sendAndStartAsync("질문")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:token")
                .contains("data:{\"delta\":\"alpha\"}")
                .contains("data:{\"delta\":\" beta\"}")
                .contains("event:done")
                .contains("\"total\":370");
    }

    @Test
    void 클라이언트_이탈로_전송이_실패해도_구독은_취소되지_않고_스트림을_끝까지_소비한다() {
        // 스펙 §5-1(A/B 공통 불변): disconnect 는 전송 중단일 뿐 생성 취소가 아니다.
        // 서버가 구독을 소유하므로, 전송이 실패해도 뒤따르는 저장·정산 신호까지 소비가 이어져야 한다.
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean consumedToEnd = new AtomicBoolean(false);
        Sinks.Many<MessageStreamEvent> events = Sinks.many().unicast().onBackpressureBuffer();
        given(aiChatMessageSendService.generateAndPersistStream(any()))
                .willReturn(events.asFlux()
                        .doOnCancel(() -> cancelled.set(true))
                        .doOnComplete(() -> consumedToEnd.set(true)));

        SseEmitter emitter = controller.sendMessage(USER_ID, 7L, new SendMessageRequest("질문"));

        events.tryEmitNext(new MessageStreamEvent.Token("앞부분"));
        // 클라이언트 이탈 모사 — 이후 전송 시도는 IllegalStateException 으로 실패한다.
        emitter.complete();
        events.tryEmitNext(new MessageStreamEvent.Token("뒷부분"));
        events.tryEmitNext(new MessageStreamEvent.Done(
                new MessageStreamEvent.TokenCount(10, 5, 15), LocalDateTime.of(2026, 5, 2, 14, 33, 21)));
        events.tryEmitComplete();

        org.assertj.core.api.Assertions.assertThat(cancelled).isFalse();
        org.assertj.core.api.Assertions.assertThat(consumedToEnd).isTrue();
    }

    @Test
    void 메시지_전송_스트림_에러_이벤트도_정상_방출된다() throws Exception {
        given(aiChatMessageSendService.generateAndPersistStream(any())).willReturn(Flux.just(
                new MessageStreamEvent.Token("부분"),
                MessageStreamEvent.Error.of(
                        AiChatErrorCode.AI_STREAM_INTERRUPTED.name(),
                        AiChatErrorCode.AI_STREAM_INTERRUPTED.getMessage()
                )
        ));

        String body = mockMvc.perform(asyncDispatch(sendAndStartAsync("질문")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:token")
                .contains("event:error")
                .contains("\"code\":\"AI_STREAM_INTERRUPTED\"");
    }

    // ── 메시지 조회 ──────────────────────────────────────────────────────

    @Test
    void 메시지_조회_정상_응답() throws Exception {
        given(aiChatMessageSearchService.findBySessionId(any()))
                .willReturn(new MessageListResult(
                        List.of(
                                new MessageResult(
                                        2L,
                                        AiChatMessage.Role.ASSISTANT,
                                        "이 책의 주제는 모험입니다.",
                                        370,
                                        AiChatMessage.Status.COMPLETED,
                                        LocalDateTime.of(2026, 5, 2, 14, 33, 22)
                                ),
                                new MessageResult(
                                        1L,
                                        AiChatMessage.Role.USER,
                                        "주제가 뭐야",
                                        null,
                                        AiChatMessage.Status.COMPLETED,
                                        LocalDateTime.of(2026, 5, 2, 14, 33, 21)
                                )
                        ),
                        1,
                        20,
                        false
                ));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].tokenCount").value(370))
                .andExpect(jsonPath("$.data.messages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.messages[1].role").value("USER"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 메시지_조회_page_가_0이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("page는 1 이상")));
    }

    @Test
    void 메시지_조회_size_가_101이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/ai-chat/sessions/7/messages")
                        .param("page", "1")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("size는 100 이하")));
    }

    // ── 감상문 조회 ──────────────────────────────────────────────────────

    @Test
    void 감상문_조회_정상_요청시_200과_감상문을_반환한다() throws Exception {
        SummaryResult result = new SummaryResult(1L, "나의 독서 감상", "깊은 울림을 주는 책이었다.");
        given(summarySearchService.findBySessionId(eq(1L), eq(USER_ID))).willReturn(result);

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("나의 독서 감상"))
                .andExpect(jsonPath("$.data.body").value("깊은 울림을 주는 책이었다."));
    }

    @Test
    void 감상문_조회_생성_요청_전이면_404() throws Exception {
        given(summarySearchService.findBySessionId(eq(1L), eq(USER_ID)))
                .willThrow(new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("아직 생성된 감상문이 없습니다."));
    }

    @Test
    void 감상문_조회_생성_중이면_409와_SUMMARY_IN_PROGRESS_메시지() throws Exception {
        given(summarySearchService.findBySessionId(eq(1L), eq(USER_ID)))
                .willThrow(new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("감상문을 생성 중입니다. 잠시 후 다시 시도해 주세요."));
    }

    // ── 감상문 초안 생성 가능 여부 조회 ──────────────────────────────────────────────

    @Test
    void eligibility_정상_요청시_200과_eligible_true를_반환한다() throws Exception {
        given(summaryDraftSearchService.findEligibility(eq(1L), eq(USER_ID)))
                .willReturn(new SummaryDraftEligibilityResult(true, null, null, 100));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary-draft/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true));
    }

    @Test
    void eligibility_잠긴_세션이면_200과_ALREADY_SUMMARIZED를_반환한다() throws Exception {
        given(summaryDraftSearchService.findEligibility(eq(1L), eq(USER_ID)))
                .willReturn(new SummaryDraftEligibilityResult(
                        false,
                        IneligibleReason.ALREADY_SUMMARIZED.name(),
                        AiChatErrorCode.SESSION_ALREADY_SUMMARIZED.getMessage(),
                        100
                ));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary-draft/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.reason").value("ALREADY_SUMMARIZED"))
                .andExpect(jsonPath("$.data.message").value("이미 감상문이 생성되어 종료된 세션입니다."));
    }

    @Test
    void eligibility_토큰_부족이면_200과_CHAT_VOLUME_NOT_ENOUGH를_반환한다() throws Exception {
        given(summaryDraftSearchService.findEligibility(eq(1L), eq(USER_ID)))
                .willReturn(new SummaryDraftEligibilityResult(
                        false,
                        IneligibleReason.CHAT_VOLUME_NOT_ENOUGH.name(),
                        AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH.getMessage(),
                        20
                ));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary-draft/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.reason").value("CHAT_VOLUME_NOT_ENOUGH"))
                .andExpect(jsonPath("$.data.message").value("감상문 초안을 생성하기에 대화량이 부족합니다."));
    }

    @Test
    void eligibility_세션이_없으면_404를_반환한다() throws Exception {
        given(summaryDraftSearchService.findEligibility(eq(1L), eq(USER_ID)))
                .willThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/ai-chat/sessions/1/summary-draft/eligibility"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("세션을 찾을 수 없습니다."));
    }

    // ── 감상문 초안 생성 (비동기) ──────────────────────────────────────────────────────

    @Test
    void 감상문_초안_정상_요청시_202를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isAccepted());
    }

    @Test
    void 감상문_초안_누적_토큰이_부족하면_422를_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new UnprocessableEntityException(AiChatErrorCode.CHAT_VOLUME_NOT_ENOUGH))
                .when(summaryDraftService).execute(any(SummaryDraftCommand.class));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("감상문 초안을 생성하기에 대화량이 부족합니다."));
    }

    @Test
    void 감상문_초안_종료된_세션이면_409를_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED))
                .when(summaryDraftService).execute(any(SummaryDraftCommand.class));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 감상문이 생성되어 종료된 세션입니다."));
    }

    @Test
    void 감상문_초안_존재하지_않는_세션이면_404를_반환한다() throws Exception {
        org.mockito.Mockito.doThrow(new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND))
                .when(summaryDraftService).execute(any(SummaryDraftCommand.class));

        mockMvc.perform(post("/api/v1/ai-chat/sessions/1/summary-draft"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("세션을 찾을 수 없습니다."));
    }
}
