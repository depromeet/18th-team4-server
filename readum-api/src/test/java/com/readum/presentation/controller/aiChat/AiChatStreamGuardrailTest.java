package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.AiChatClient;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.entity.BookFixture;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.entity.UserBookFixture;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private Cookie cookie;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.create(UUID.randomUUID(), "책읽는여우"));
        cookie = new Cookie("user_session", user.getSessionId());

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

    private org.springframework.test.web.servlet.ResultActions send(String content) throws Exception {
        // Accept 헤더를 지정하지 않는다(= accept all). 통과 시 produces=text/event-stream 매칭이 되고,
        // 거부/장애 시 JSON 에러 본문도 content negotiation 으로 정상 반환된다.
        // (Accept: text/event-stream 만 보내면 JSON 에러 본문이 협상에 실패해 ServletException 으로 샌다.)
        SendMessageRequest body = new SendMessageRequest(content);
        return mockMvc.perform(post("/api/v1/ai-chat/sessions/" + sessionId + "/messages")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
    void 통과하면_async_스트림이_시작되고_USER_메시지가_COMPLETED_로_저장된다() throws Exception {
        given(inputModerationClient.check(any(), any())).willReturn(InputModerationResult.passed());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Token("이 책은"),
                new AiChatChunk.Completion(10, 5, 15, null)
        ));

        send("이 책의 줄거리를 요약해줘")
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());

        assertThat(countByStatus("COMPLETED")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 거부_후_후속_정상_요청은_정상_스트림을_시작한다() throws Exception {
        given(inputModerationClient.check(eq("차단 질문"), any()))
                .willReturn(InputModerationResult.blocked(java.util.List.of("self-harm")));
        given(inputModerationClient.check(eq("정상 질문"), any()))
                .willReturn(InputModerationResult.passed());
        given(aiChatClient.stream(any(AiChatStreamCommand.class))).willReturn(Flux.just(
                new AiChatChunk.Completion(10, 5, 15, null)
        ));

        send("차단 질문").andExpect(status().isBadRequest());
        send("정상 질문")
                .andExpect(request().asyncStarted())
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
