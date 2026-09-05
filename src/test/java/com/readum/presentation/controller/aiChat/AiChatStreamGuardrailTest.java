package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.BookFixture;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.userBook.entity.UserBook;
import com.readum.model.userBook.entity.UserBookFixture;
import com.readum.model.userBook.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 입력 가드레일의 HTTP→서비스→DB 전 경로를 검증하는 E2E 슬라이스.
 * InputModerationClient 만 mocking 해 판별 결과를 주입하고, SendService/PersistService/Repository/
 * Session/User 는 실제 빈 + H2 로 동작시킨다.
 * AiChatClient 도 mocking 한다 — 실제 LLM(OpenAI) 호출 없이 통과 경로의 스트림만 흉내내기 위함(테스트 격리).
 */
@Tag("guardrail")
@SpringBootTest
@AutoConfigureMockMvc
class AiChatStreamGuardrailTest {

    private static final String REJECT_MESSAGE = "요청을 처리할 수 없습니다. 독서와 관련된 질문으로 다시 요청해 주세요.";

    // 커밋 후 리스너(제목 생성 등)를 같은 스레드에서 실행해 테스트 실행 시점을 결정적으로 만든다.
    // 메시지 전송 경로 자체는 이 executor 를 쓰지 않는다 — 선행 처리는 요청 스레드에서 동기로 끝나고,
    // 생성 구간은 공유 boundedElastic 위에서 돌며 완료를 async dispatch 로 기다린다.
    @TestConfiguration
    static class DirectExecutorConfig {
        @Bean
        @Primary
        Executor directAiChatVirtualThreadExecutor() {
            return Runnable::run;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private UserBookRepository userBookRepository;

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private InputModerationClient inputModerationClient;

    @MockitoBean
    private AiChatClient aiChatClient;

    // 토큰 예산은 실제 빈(UserTokenBudgetWriter) + H2 원장으로 동작한다 — 일일 예산이 커서 항상 허용된다.
    // 전역 게이트는 실제 Redis 없이 항상 허용시킨다 (이 슬라이스는 moderation 검증용).
    @MockitoBean
    private OpenAiRequestGate openAiRequestGate;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private Long userId;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        given(openAiRequestGate.tryAcquire(anyString(), anyInt()))
                .willReturn(new OpenAiRequestGate.Decision.Permitted(
                        new OpenAiRequestGate.GateReservation("gpt-4o-mini", 29_000_000L, 1000)));
        // AiChatClient 자체가 mock 이므로 게이트 확보도 여기서 직접 통과시킨다 (계상 없는 permit).
        given(aiChatClient.acquireRateLimitPermit(any(AiChatStreamCommand.class)))
                .willReturn(new AiChatClient.RateLimitPermit.Uncounted());

        User user = userRepository.save(User.create(UUID.randomUUID(), "책읽는여우"));
        userId = user.getId();

        Book book = bookRepository.save(BookFixture.persistedBook(
                null, "guardrail-ext-" + UUID.randomUUID(), "살인의 추억", "작가", "출판사", 2003, null));
        UserBook userBook = userBookRepository.save(
                UserBookFixture.persistedUserBook(null, user.getId(), book.getId()));
        // userMessageCount=1 로 시작해 통과 경로에서 첫-메시지 제목 생성(LLM) 트리거를 피한다.
        AiChatSession session = aiChatSessionRepository.save(
                AiChatSessionFixture.persistedActiveSession(null, userBook.getId(), 1, 0, "기존 제목"));
        sessionId = session.getId();
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ? AND status = ?",
                Long.class, sessionId, status);
        return count == null ? 0 : count;
    }

    /**
     * 요청을 보내고, 비동기로 시작됐으면 async dispatch 까지 태워 최종 응답을 돌려준다.
     * 선행 처리 거절(모더레이션 차단·불능 등)은 SSE 시작 전 동기 예외라 그대로 동기 응답이고,
     * 통과 경로만 SSE 로 비동기 시작돼 async dispatch 를 태워야 본문이 나온다.
     */
    private org.springframework.test.web.servlet.ResultActions send(String content) throws Exception {
        // Accept 헤더를 지정하지 않는다(= accept all). 통과 시 produces=text/event-stream 매칭이 되고,
        // 거부/장애 시 JSON 에러 본문도 content negotiation 으로 정상 반환된다.
        // (Accept: text/event-stream 만 보내면 JSON 에러 본문이 협상에 실패해 ServletException 으로 샌다.)
        SendMessageRequest body = new SendMessageRequest(content);
        org.springframework.test.web.servlet.ResultActions actions =
                mockMvc.perform(post("/api/v1/ai-chat/sessions/" + sessionId + "/messages")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)));
        MvcResult started = actions.andReturn();
        if (started.getRequest().isAsyncStarted()) {
            return mockMvc.perform(asyncDispatch(started));
        }
        return actions;
    }

    /** 실제 스트리밍 응답과 같은 모양: 본문 조각 1건 + 실측 사용량이 실린 마지막 조각. */
    private void givenGeneratedStream(String content) {
        given(aiChatClient.generateStream(any(AiChatStreamCommand.class)))
                .willReturn(Flux.just(
                        AiChatStreamChunk.ofDelta(content),
                        new AiChatStreamChunk("", 10, 5, 15)));
    }

    @Test
    void 입력이_차단되면_400_과_거부_정본_JSON_을_응답하고_USER_메시지가_REJECTED_로_저장된다() throws Exception {
        given(inputModerationClient.check(eq("살인범이 누구야?"), any()))
                .willReturn(InputModerationResult.blocked(java.util.List.of("violence")));

        send("살인범이 누구야?")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("독서와 관련된 질문")));

        assertThat(countByStatus("REJECTED")).isEqualTo(1L);
        assertThat(countByStatus("COMPLETED")).isZero();
    }

    @Test
    void 차단_응답_메시지는_거부_정본_텍스트와_정확히_일치한다() throws Exception {
        given(inputModerationClient.check(any(), any()))
                .willReturn(InputModerationResult.blocked(java.util.List.of("self-harm")));

        send("차단 대상")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(REJECT_MESSAGE));
    }

    @Test
    void Moderation_API_장애_UNAVAILABLE_이면_503_과_RetryAfter_헤더를_응답하고_메시지를_저장하지_않는다() throws Exception {
        given(inputModerationClient.check(any(), any()))
                .willReturn(InputModerationResult.serviceUnavailable("OpenAI Moderation 502"));

        send("질문")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        assertThat(countByStatus("REJECTED")).isZero();
        assertThat(countByStatus("COMPLETED")).isZero();
    }

    @Test
    void 통과하면_스트림이_완주하고_USER_메시지가_COMPLETED_로_저장된다() throws Exception {
        given(inputModerationClient.check(any(), any())).willReturn(InputModerationResult.passed());
        givenGeneratedStream("이 책은");

        send("이 책의 줄거리를 요약해줘")
                .andExpect(status().isOk());

        assertThat(countByStatus("COMPLETED")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 거부_후_후속_정상_요청은_정상_스트림을_시작한다() throws Exception {
        given(inputModerationClient.check(eq("차단 질문"), any()))
                .willReturn(InputModerationResult.blocked(java.util.List.of("self-harm")));
        given(inputModerationClient.check(eq("정상 질문"), any()))
                .willReturn(InputModerationResult.passed());
        givenGeneratedStream("정상 응답");

        send("차단 질문").andExpect(status().isBadRequest());
        send("정상 질문")
                .andExpect(status().isOk());

        assertThat(countByStatus("REJECTED")).isEqualTo(1L);
        assertThat(countByStatus("COMPLETED")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void G10_4000자_초과_입력은_400_으로_거부된다() throws Exception {
        send("안".repeat(4001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("4000자")));
    }

    @Test
    void 빈_메시지는_400_으로_거부된다() throws Exception {
        send("   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("비어")));
    }
}
