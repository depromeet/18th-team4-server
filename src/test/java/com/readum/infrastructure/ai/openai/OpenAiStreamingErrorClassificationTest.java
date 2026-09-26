package com.readum.infrastructure.ai.openai;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.exception.TooManyRequestsException;
import com.readum.infrastructure.ai.openai.availability.AiProviderFailureClassifier;
import com.readum.infrastructure.ai.openai.availability.AiProviderFailureKind;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 스트리밍의 HTTP 오류가 단발 호출과 <b>같은 기준</b>으로 분류되는지 — 실제 {@link OpenAiChatModel}
 * 의 스트림 경로를 로컬 모의 HTTP 서버에 붙여 확인한다.
 *
 * <p><b>이 테스트가 막는 결함.</b> Spring AI 의 {@code OpenAiApi} 는 우리가 준 오류 핸들러를
 * {@code RestClient} 에만 연결하고 {@code WebClient} 에는 붙이지 않는다(2.0.0-M4 바이트코드 확인).
 * 그래서 채팅 스트림의 429·401·503 은 우리 분류를 거치지 않고 {@code WebClientResponseException} 으로
 * 올라갔고, 그 타입에는 분류 규칙이 없어 <b>"세지 않는 실패" 로 묻혔다</b> — 결제가 막혀도, 키가 틀려도,
 * 공급자가 과부하라고 말해도 채팅의 장애 상태에는 아무것도 쌓이지 않았다.
 * 모의 모델로 감싼 테스트로는 이 결함이 드러나지 않는다. 그래서 여기서는 <b>실제 전송 계층</b>을 태운다.
 *
 * <p>모의 서버는 루프백에 뜨고 키도 가짜다 — 외부 유료 호출은 하지 않는다.
 */
class OpenAiStreamingErrorClassificationTest {

    private static final String MODEL = "gpt-4o-mini";

    private final AiProviderFailureClassifier classifier = new AiProviderFailureClassifier();

    private HttpServer server;
    private final AtomicReference<Response> nextResponse = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 모의 서버가 돌려줄 응답. */
    private record Response(int status, String body, String retryAfter) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
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

    // --- 분류 -----------------------------------------------------------------------------------

    @Test
    void 스트림의_429_slow_down_은_속도_제한으로_분류된다() {
        given(429, """
                {"error":{"message":"too quickly","type":"rate_limit_error","code":"slow_down"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.throwable(Throwable.class))
                .satisfies(error -> {
                    TooManyRequestsException rateLimited = findCause(error, TooManyRequestsException.class);
                    assertThat(rateLimited.getErrorCode()).isEqualTo(AiChatErrorCode.AI_PROVIDER_RATE_LIMITED);
                    assertThat(classifier.classify(error).kind()).isEqualTo(AiProviderFailureKind.RATE_LIMIT);
                });
    }

    @Test
    void 스트림의_429_credit_balance_exhausted_는_결제_소진으로_분류된다() {
        given(429, """
                {"error":{"message":"no credits","type":"rate_limit_error","code":"credit_balance_exhausted"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock).satisfies(error -> {
            TooManyRequestsException exhausted = findCause(error, TooManyRequestsException.class);
            assertThat(exhausted.getErrorCode()).isEqualTo(AiChatErrorCode.AI_QUOTA_EXHAUSTED);
            assertThat(classifier.classify(error).kind()).isEqualTo(AiProviderFailureKind.QUOTA);
        });
    }

    @Test
    void 스트림의_401_은_키_권한_문제로_분류된다() {
        given(401, """
                {"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock).satisfies(error -> {
            AiDependencyUnavailableException authError =
                    findCause(error, AiDependencyUnavailableException.class);
            assertThat(authError.getErrorCode()).isEqualTo(AiChatErrorCode.AI_PROVIDER_AUTH_ERROR);
            assertThat(classifier.classify(error).kind()).isEqualTo(AiProviderFailureKind.AUTH);
        });
    }

    @Test
    void 스트림의_503_과부하는_일시_실패로_분류되고_공급자가_준_대기를_보존한다() {
        given(503, """
                {"error":{"message":"The model is overloaded","type":"service_unavailable_error","code":"server_is_overloaded"}}
                """, "45");

        assertThatThrownBy(this::streamAndBlock).satisfies(error -> {
            OpenAiTransientException overloaded = findCause(error, OpenAiTransientException.class);
            assertThat(overloaded.getProviderErrorCode()).isEqualTo("server_is_overloaded");
            AiProviderFailureClassifier.Classification classification = classifier.classify(error);
            assertThat(classification.kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
            assertThat(classification.retryAfter()).isEqualTo(Duration.ofSeconds(45));
        });
    }

    @Test
    void 스트림의_400_은_그_요청의_문제라_공급자_상태로_세지_않는다() {
        given(400, """
                {"error":{"message":"too long","type":"invalid_request_error","code":"context_length_exceeded"}}
                """, null);

        assertThatThrownBy(this::streamAndBlock).satisfies(error -> {
            assertThat(findCause(error, NonTransientAiException.class)).isNotNull();
            assertThat(classifier.classify(error).kind()).isEqualTo(AiProviderFailureKind.NOT_COUNTED);
        });
    }

    @Test
    void 오류_응답에도_재시도를_보내지_않는다() {
        // 재시도는 과부하 때 실패를 되먹여 상황을 키우는 증폭기였다(#103 교훈).
        given(429, "{}", null);

        assertThatThrownBy(this::streamAndBlock);

        assertThat(requestCount.get()).isEqualTo(1);
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    private void given(int status, String body, String retryAfter) {
        nextResponse.set(new Response(status, body, retryAfter));
    }

    private void streamAndBlock() {
        streamingChatModel().stream(new Prompt(new UserMessage("ping"))).blockLast(Duration.ofSeconds(10));
    }

    /** 운영 구성과 같은 모양 — 스트림용 WebClient 에 우리 분류 필터를 얹은 실제 {@link OpenAiChatModel}. */
    private OpenAiChatModel streamingChatModel() {
        OpenAiErrorResponseTranslator translator =
                new OpenAiErrorResponseTranslator(JsonMapper.builder().build(), OpenAiProject.CHAT, 300L);
        ExchangeFilterFunction errorFilter = OpenAiStreamingErrorFilter.of(translator);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey("test-key-not-real")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .restClientBuilder(RestClient.builder())
                .webClientBuilder(WebClient.builder().filter(errorFilter))
                .responseErrorHandler(new OpenAiResponseErrorHandler(
                        JsonMapper.builder().build(), OpenAiProject.CHAT, 300L))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(MODEL).streamUsage(true).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
    }

    /** 원인 사슬을 따라 내려가 찾는다 — 전송 계층이 우리 예외를 한두 겹 감싸 던질 수 있다. */
    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        throw new AssertionError("기대한 원인을 찾지 못했다: " + type.getSimpleName() + " — 실제: " + error);
    }
}
