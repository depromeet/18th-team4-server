package com.readum.infrastructure.ai.openai.contextsummary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.infrastructure.ai.openai.OpenAiResponseErrorHandlerFactory;
import com.readum.infrastructure.ai.openai.availability.AiProviderCallGuard;
import com.readum.infrastructure.ai.openai.availability.ProtectedChatModel;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProjectProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 컨텍스트 요약 전용 ChatClient.
 *
 * 컨텍스트 요약 워커는 공유 채팅 ChatClient(.call() 경로)를 그대로 쓰면 응답이 없을 때 무한 대기한다
 * — 그 클라이언트는 채팅 스트리밍용이라 responseTimeout 이 없다. 백그라운드 워커의 스레드/연결이
 * 매달리지 않도록 전용 HTTP 클라이언트에 responseTimeout 을 걸어 격리한다(채팅·moderation 경로엔 영향 없음).
 * 제목 생성 전용 ChatClient(AiChatTitleClientConfig)와 같은 패턴이다.
 *
 * - responseTimeout 30초: 구조화 요약은 한 줄 제목보다 출력이 크므로 제목(10초)보다 넉넉히 두되,
 *   무한 대기만은 막는 상한. 초과 시 예외로 드러나 워커가 재시도 가능 실패로 재큐한다.
 */
@Slf4j
@Configuration
public class AiContextSummaryClientConfig {

    private static final Duration CONTEXT_SUMMARY_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public ChatClient contextSummaryChatClient(
            OpenAiResponseErrorHandlerFactory errorHandlerFactory,
            AiProviderCallGuard callGuard,
            OpenAiProjectProperties projectProperties,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String chatModelName
    ) {
        HttpClient httpClient = HttpClient.create().responseTimeout(CONTEXT_SUMMARY_RESPONSE_TIMEOUT);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(projectProperties.apiKeyOf(OpenAiProject.CONTEXT_SUMMARY))
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(errorHandlerFactory.create(OpenAiProject.CONTEXT_SUMMARY))
                .build();

        // 자체 재시도를 두지 않는다. 재시도 정책은 작업 큐가 소유한다 — 모델이 안에서 한 번 더 되풀이하면
        // 큐가 세는 시도 횟수 하나가 실제로는 여러 번의 과금 호출이 되어, 정책이 두 겹으로 어긋난다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(chatModelName).build())
                .retryTemplate(new RetryTemplate(noRetry))
                .build();

        log.info("[AiChat] 컨텍스트 요약 전용 ChatClient 구성 - model={}, responseTimeout={}",
                chatModelName, CONTEXT_SUMMARY_RESPONSE_TIMEOUT);
        return ChatClient.builder(
                new ProtectedChatModel(chatModel, AiAvailability.Capability.CONTEXT_SUMMARY, callGuard)).build();
    }
}
