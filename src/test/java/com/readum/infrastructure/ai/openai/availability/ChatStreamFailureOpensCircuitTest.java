package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.infrastructure.ai.openai.OpenAiErrorResponseTranslator;
import com.readum.infrastructure.ai.openai.OpenAiResponseErrorHandler;
import com.readum.infrastructure.ai.openai.OpenAiStreamingErrorFilter;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 스트리밍이 실제 HTTP 오류를 받았을 때 <b>정말로 그 기능의 차단이 열리는지</b>를 끝에서 끝까지 본다 —
 * 모의 HTTP 서버 → 실제 {@link OpenAiChatModel} 스트림 → 오류 분류 → 보호 계층 → 차단기까지.
 *
 * <p>조각을 따로 확인하면 가운데가 끊겨 있어도 모두 통과한다. 실제로 그런 적이 있다 — 스트리밍 경로에
 * 오류 분류가 붙지 않아 429 가 "세지 않는 실패" 로 묻혔고, 사용자가 가장 많이 쓰는 경로에서 보호가 통째로
 * 빠져 있었다. 그래서 여기서는 중간을 하나도 흉내 내지 않는다.
 *
 * <p>모의 서버는 루프백에 뜨고 키도 가짜다 — 외부 유료 호출은 하지 않는다.
 */
class ChatStreamFailureOpensCircuitTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final long START_MILLIS = 1_700_000_000_000L;
    private static final long RATE_LIMIT_BLOCK_SECONDS = 30;
    private static final long QUOTA_BLOCK_SECONDS = 300;

    private final AtomicLong now = new AtomicLong(START_MILLIS);
    private final AtomicReference<Response> nextResponse = new AtomicReference<>();

    private HttpServer server;
    private AiProviderCircuitBreaker circuitBreaker;
    private ChatModel protectedChatModel;

    private record Response(int status, String body, String retryAfter) {
    }

    @BeforeEach
    void startServer() throws IOException {
        now.set(START_MILLIS);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.setExecutor(null);
        server.start();

        circuitBreaker = new AiProviderCircuitBreaker(properties(), now::get);
        AiProviderCallGuard callGuard =
                new AiProviderCallGuard(circuitBreaker, new AiProviderFailureClassifier());
        protectedChatModel = new ProtectedChatModel(
                streamingChatModel(), AiAvailability.Capability.CHAT, callGuard);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 스트림이_받은_429_한_건이_다음_호출을_막는다() {
        given(429, """
                {"error":{"message":"too quickly","type":"rate_limit_error","code":"slow_down"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock);

        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .as("공급자가 직접 한도 초과라고 말했다 — 여러 건이 쌓이기를 기다릴 이유가 없다")
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.stateOf(AiAvailability.Capability.CHAT))
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 결제_소진은_한도_초과보다_오래_막고_시간만으로는_풀리지_않는다() {
        given(429, """
                {"error":{"message":"no credits","type":"rate_limit_error","code":"credit_balance_exhausted"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock);

        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT))
                .as("결제가 막힌 프로젝트를 30초 만에 두드리면 같은 응답을 다시 받을 뿐이다")
                .isEmpty();

        advanceSeconds(QUOTA_BLOCK_SECONDS);
        assertThatThrownBy(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .as("확인 시각이 됐다는 것은 시험해 볼 때라는 뜻이지 통과시켜도 된다는 뜻이 아니다")
                .isInstanceOf(AiDependencyUnavailableException.class);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isPresent();
    }

    @Test
    void 공급자가_준_Retry_After_가_차단_시간이_된다() {
        given(429, """
                {"error":{"message":"slow down","type":"rate_limit_error","code":"slow_down"}}
                """, "120");

        assertThatThrownBy(this::streamAndBlock);

        advanceSeconds(119);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT))
                .as("공급자가 말한 시각 안에 두드리면 똑같이 거절당한다")
                .isEmpty();
        advanceSeconds(2);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT)).isPresent();
    }

    @Test
    void 인증_오류도_한_건으로_바로_오래_막는다() {
        given(401, """
                {"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock);

        assertThat(circuitBreaker.stateOf(AiAvailability.Capability.CHAT))
                .isEqualTo(CircuitBreaker.State.OPEN);
        advanceSeconds(RATE_LIMIT_BLOCK_SECONDS + 1);
        assertThat(circuitBreaker.admitProbe(AiAvailability.Capability.CHAT))
                .as("사람이 키를 고쳐야 풀리는 종류다 — 30초 만에 다시 두드리지 않는다")
                .isEmpty();
    }

    @Test
    void 그_요청_하나의_입력_문제는_기능을_차단하지_않는다() {
        // 400 은 다시 보내도 같은 결과다 — 공급자가 죽은 것이 아니다. 이것으로 차단하면
        // 사용자 한 명의 잘못된 입력이 모두의 채팅을 멈춘다.
        given(400, """
                {"error":{"message":"too long","type":"invalid_request_error","code":"context_length_exceeded"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock);

        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.CHAT))
                .doesNotThrowAnyException();
    }

    @Test
    void 한_기능이_막혀도_다른_기능은_그대로_나간다() {
        given(429, """
                {"error":{"message":"too quickly","type":"rate_limit_error","code":"slow_down"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock);

        assertThatCode(() -> circuitBreaker.admit(AiAvailability.Capability.TITLE))
                .as("차단기는 기능마다 하나다 — 채팅의 429 가 제목 생성을 멈추지 않는다")
                .doesNotThrowAnyException();
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    private static AiAvailabilityProperties properties() {
        return new AiAvailabilityProperties(
                60, 10, 50,
                30,                         // 일시 실패 차단 시간
                RATE_LIMIT_BLOCK_SECONDS,   // 속도 제한 차단 시간
                QUOTA_BLOCK_SECONDS,        // 결제·지출 한도 차단 시간
                300, 600, 30);
    }

    private void given(int status, String body, String retryAfter) {
        nextResponse.set(new Response(status, body, retryAfter));
    }

    private void respond(HttpExchange exchange) throws IOException {
        Response response = nextResponse.get();
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (response.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
        }
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void streamAndBlock() {
        protectedChatModel.stream(new Prompt(new UserMessage("ping"))).blockLast(Duration.ofSeconds(10));
    }

    /** 운영 구성과 같은 모양 — 스트림용 WebClient 에 우리 분류 필터를 얹은 실제 {@link OpenAiChatModel}. */
    private OpenAiChatModel streamingChatModel() {
        OpenAiErrorResponseTranslator translator =
                new OpenAiErrorResponseTranslator(JsonMapper.builder().build(), OpenAiProject.CHAT, 300L);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey("test-key-not-real")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .restClientBuilder(RestClient.builder())
                .webClientBuilder(WebClient.builder().filter(OpenAiStreamingErrorFilter.of(translator)))
                .responseErrorHandler(new OpenAiResponseErrorHandler(
                        JsonMapper.builder().build(), OpenAiProject.CHAT, 300L))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(MODEL).streamUsage(true).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
    }

    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1000L);
    }
}
