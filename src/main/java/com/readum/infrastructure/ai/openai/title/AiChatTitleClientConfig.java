package com.readum.infrastructure.ai.openai.title;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 제목 생성 전용 ChatClient.
 *
 * 채팅과 같은 공유 ChatClient(.call() 경로)를 쓰면 "제목 생성만 read 타임아웃" 을 줄 수 없어,
 * 전용 HTTP 클라이언트를 따로 만들어 격리한다(채팅·moderation 경로엔 영향 없음).
 *
 * - responseTimeout 10초: 제목 생성 LLM 호출이 응답 없이 매달릴 때 끊어, 제목 생성 전용 스케줄러의
 *   스레드/연결이 무한정 묶이지 않게 한다(thread-cap 을 크게 둬도 blast-radius 를 시간으로 가둠).
 * - advisor 미적용: 입력(유저 첫 메시지)은 이미 채팅 전송 시 moderation 을 통과했고, 출력 moderation
 *   advisor 를 태우면 제목 생성마다 moderation 호출이 추가로 붙어 비용·지연만 늘어난다.
 */
@Slf4j
@Configuration
public class AiChatTitleClientConfig {

    private static final Duration TITLE_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public ChatClient titleGenerationChatClient(
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String chatModelName
    ) {
        HttpClient httpClient = HttpClient.create().responseTimeout(TITLE_RESPONSE_TIMEOUT);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(openAiResponseErrorHandler)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(chatModelName).build())
                .build();

        log.info("[AiChat] 제목 생성 전용 ChatClient 구성 - model={}, responseTimeout={}",
                chatModelName, TITLE_RESPONSE_TIMEOUT);
        return ChatClient.builder(chatModel).build();
    }
}
