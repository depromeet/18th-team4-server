package com.readum.infrastructure.ai.openai.chat;

import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.service.AiChatGenerationAccumulator;
import com.readum.domain.exception.BusinessException;
import com.readum.infrastructure.ai.audit.AiPromptAuditLogger;
import com.readum.infrastructure.ai.openai.guardrail.ChatInputGuardrail;
import com.readum.infrastructure.ai.openai.guardrail.GuardrailProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring AI 2.0.0-M4 의 {@code OpenAiChatModel} 을 <b>실제로 구동해</b> 스트림 응답 계약을 확인한다.
 *
 * <p>가짜 OpenAI SSE 서버(JDK {@code HttpServer})를 띄우고 그 앞에 실제 모델을 붙인다. 즉 여기서 보는 것은
 * mock 으로 흉내 낸 모양이 아니라 <b>모델 입력(SSE 본문) → Spring AI 변환 → 애플리케이션 DTO</b> 까지의
 * 실제 경로다. 외부 OpenAI 를 호출하지 않으므로 기본 빌드에서 그대로 돈다.
 *
 * <p>확인 대상은 셋이다.
 * <ol>
 *   <li>종료 사유·사용량이 DTO 까지 보존되는가</li>
 *   <li>본문 없는 정상 청크(역할 전용·종료 사유 전용·사용량 전용)를 오류로 오판하지 않는가</li>
 *   <li>M4 가 변환 예외를 삼키고 빈 응답으로 바꾸는 경로가 실제로 재현되는가, 그리고 그 검사가
 *       무엇을 잡고 무엇을 못 잡는가</li>
 * </ol>
 */
class OpenAiChatModelStreamContractTest {

    private static final String CHUNK_ROLE_ONLY = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""";
    private static final String CHUNK_DELTA = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[{"index":0,"delta":{"content":"이 책은"},"finish_reason":null}]}""";
    private static final String CHUNK_FINISH_STOP = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""";
    private static final String CHUNK_FINISH_LENGTH = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""";
    private static final String CHUNK_USAGE_ONLY = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";
    /** choices 항목에 delta 가 통째로 빠진 응답 — M4 의 변환이 예외를 내고 빈 응답으로 바뀌는 입력이다. */
    private static final String CHUNK_WITHOUT_DELTA = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini",\
            "choices":[{"index":0,"finish_reason":null}]}""";

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void 모델_입력의_종료_사유와_실측_사용량이_청크_DTO_까지_보존된다() {
        List<AiChatStreamChunk> chunks = streamChunks(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), true);

        assertThat(chunks).anyMatch(chunk -> "STOP".equals(chunk.finishReason()));
        AiChatStreamChunk usageChunk = chunks.stream().filter(AiChatStreamChunk::hasValidUsage)
                .reduce((first, second) -> second).orElseThrow();
        assertThat(usageChunk.inputTokens()).isEqualTo(10);
        assertThat(usageChunk.outputTokens()).isEqualTo(5);
        assertThat(usageChunk.totalTokens()).isEqualTo(15);
    }

    @Test
    void 정상_스트림은_본문_누적과_함께_정상_완료로_판정된다() {
        AiChatGenerationOutcome outcome = judge(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), true);

        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.SUCCESS);
        assertThat(outcome.content()).isEqualTo("이 책은");
        assertThat(outcome.totalTokens()).isEqualTo(15);
    }

    @Test
    void 종료_표식_없이_스트림이_끝나도_종료_사유와_사용량이_갖춰졌으면_정상_완료다() {
        // Spring AI 는 [DONE] 표식을 내부에서 걷어내 애플리케이션까지 보내지 않는다.
        // 표식 관찰을 성공 조건에 넣지 않는다는 계약을 여기서 확인한다.
        AiChatGenerationOutcome outcome = judge(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), false);

        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.SUCCESS);
    }

    @Test
    void 본문_없이_역할이나_사용량만_실린_정상_청크를_오류로_오판하지_않는다() {
        List<AiChatStreamChunk> chunks = streamChunks(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), true);

        // 하나라도 변환 손실로 걸리면 정상 스트림을 오류로 끊는 셈이다.
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(chunk -> !chunk.hasDelta());
    }

    @Test
    void 종료_사유와_사용량을_받기_전에_스트림이_끊기면_정상_완료가_아니다() {
        // 본문은 남아 있다. 즉 "본문이 비지 않았다"는 검사만으로는 중간 손실을 걸러내지 못한다 —
        // 종료 사유와 사용량 조건이 있어야 잡힌다.
        AiChatGenerationOutcome outcome = judge(List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA), true);

        assertThat(outcome.content()).isEqualTo("이 책은");
        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.NO_FINISH_REASON);
    }

    @Test
    void 종료_사유는_왔지만_사용량이_끝내_오지_않으면_정상_완료가_아니다() {
        AiChatGenerationOutcome outcome = judge(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_STOP), true);

        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.NO_USAGE);
    }

    @Test
    void 잘린_응답은_사용량이_있어도_정상_완료가_아니다() {
        AiChatGenerationOutcome outcome = judge(
                List.of(CHUNK_ROLE_ONLY, CHUNK_DELTA, CHUNK_FINISH_LENGTH, CHUNK_USAGE_ONLY), true);

        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.ABNORMAL_FINISH_REASON);
        assertThat(outcome.finishReason()).isEqualTo("LENGTH");
    }

    @Test
    void 변환_예외가_난_응답은_본문도_식별자도_사용량도_없는_빈_응답으로_바뀐다() {
        // M4 는 변환 예외를 로그로만 남기고 빈 ChatResponse 로 바꿔 흘려보낸다.
        // 애플리케이션에 도착하는 그 응답의 모양을 실제 모델 구동으로 확인한다.
        List<ChatResponse> responses = rawResponses(
                List.of(CHUNK_ROLE_ONLY, CHUNK_WITHOUT_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), true);

        ChatResponse converted = responses.stream()
                .filter(response -> response.getResult() == null && response.getMetadata().getId().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("변환 실패 응답이 재현되지 않았다"));
        assertThat(converted.getResults()).isEmpty();
        assertThat(converted.getMetadata().getId()).isBlank();
        assertThat(converted.getMetadata().getUsage().getTotalTokens()).isZero();
    }

    @Test
    void 변환_손실이_의심되는_응답을_만나면_스트림을_오류로_끊는다() {
        assertThatThrownBy(() -> generateStreamChunks(
                List.of(CHUNK_ROLE_ONLY, CHUNK_WITHOUT_DELTA, CHUNK_FINISH_STOP, CHUNK_USAGE_ONLY), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 공급자가_오류_응답을_주면_스트림이_오류로_끝난다() {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"boom\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        assertThatThrownBy(() -> Flux.from(chatModel().stream(prompt())).collectList().block(Duration.ofSeconds(15)))
                .isInstanceOf(RuntimeException.class);
    }

    // --- 실행 도우미 ---

    /** 모델 스트림을 그대로 받는다 (Spring AI 변환 결과 원본 확인용). */
    private List<ChatResponse> rawResponses(List<String> payloads, boolean withDoneMarker) {
        startServer(sseHandler(payloads, withDoneMarker));
        return Flux.from(chatModel().stream(prompt())).collectList().block(Duration.ofSeconds(15));
    }

    /** 모델 스트림을 애플리케이션 DTO 로 옮긴 결과. 변환 손실 검사는 거치지 않는다. */
    private List<AiChatStreamChunk> streamChunks(List<String> payloads, boolean withDoneMarker) {
        AiChatClientImpl mapper = aiChatClient(null);
        return rawResponses(payloads, withDoneMarker).stream().map(mapper::toStreamChunk).toList();
    }

    /** 어댑터(AiChatClientImpl#generateStream)를 통째로 태운 결과 — 변환 손실 검사를 포함한 실제 경로. */
    private List<AiChatStreamChunk> generateStreamChunks(List<String> payloads, boolean withDoneMarker) {
        startServer(sseHandler(payloads, withDoneMarker));
        return aiChatClient(chatModel())
                .generateStream(new AiChatStreamCommand(
                        1L, List.of(new HistoryMessage(HistoryMessage.Role.USER, "이 책 어때?")), null, null))
                .collectList()
                .block(Duration.ofSeconds(15));
    }

    /** 청크를 순서대로 누적기에 넣고 정상 완료 판정까지 받는다. */
    private AiChatGenerationOutcome judge(List<String> payloads, boolean withDoneMarker) {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        streamChunks(payloads, withDoneMarker).forEach(accumulator::accept);
        return accumulator.completeNormally();
    }

    private AiChatClientImpl aiChatClient(OpenAiChatModel model) {
        AiChatClientImpl client = new AiChatClientImpl(
                model,
                new ChatInputGuardrail(GuardrailProperties.Input.defaults()),
                new AiPromptAuditLogger(JsonMapper.builder().build()),
                null, null, null);
        ReflectionTestUtils.setField(client, "baseSystemPrompt", "너는 독서 도우미다.");
        return client;
    }

    private Prompt prompt() {
        return new Prompt(List.of(new UserMessage("이 책 어때?")));
    }

    private OpenAiChatModel chatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + httpServer.getAddress().getPort())
                .restClientBuilder(RestClient.builder())
                .webClientBuilder(WebClient.builder())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o-mini").streamUsage(true).build())
                .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
                .build();
    }

    private void startServer(HttpHandler handler) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", handler);
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException("가짜 OpenAI 서버를 띄우지 못했다", e);
        }
    }

    /** OpenAI 스트리밍 응답과 같은 모양으로 SSE 를 흘려보낸다. withDoneMarker=false 면 [DONE] 없이 본문이 끝난다. */
    private HttpHandler sseHandler(List<String> payloads, boolean withDoneMarker) {
        return (HttpExchange exchange) -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String payload : payloads) {
                    out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                if (withDoneMarker) {
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        };
    }
}
