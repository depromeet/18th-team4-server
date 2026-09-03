package com.readum.infrastructure.ai.openai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.api.OpenAiModerationApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 입력 moderation 클라이언트 구성.
 * 단순 요청/응답이라 리액티브가 불필요 → 블로킹 JDK HttpClient 로 구성하고 가상 스레드에서 블로킹한다.
 * (리액티브로 감싸면 동기 호출이 reactor boundedElastic 스케줄러(기본 10×CPU)로 offload 되어 그 캡에 직렬화된다 — 측정 확인.)
 */
@Slf4j
@Configuration
public class OpenAiHttpClientConfig {

    /**
     * moderation ModerationModel 을 블로킹 JDK HttpClient(HTTP/1.1) 로 구성 (auto-config 대체).
     * reactor 클라이언트로 감싸지 않으므로 boundedElastic offload·그 캡(10×CPU)이 없다 — 가상 스레드에서 그냥 블로킹.
     * 단순 1회성 호출이라 H2 멀티플렉싱 이점이 없어 HTTP/1.1 로 고정(H2 스택 배제). JDK 자체 연결 풀을 그대로 쓴다.
     */
    @Bean
    @Primary
    public ModerationModel moderationModel(
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl
    ) {
        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                // moderation 은 단순 1회성 요청/응답이라 H2 멀티플렉싱 이점이 없다. HTTP/1.1 로 고정해
                // H2 스택(스트림 멀티플렉싱·플로우컨트롤)을 아예 배제 → 가드레일 경로를 단순·예측가능하게 둔다.
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30)); // moderation 이 매달리지 않게 상한(② read 타임아웃)
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        OpenAiModerationApi api = OpenAiModerationApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(openAiResponseErrorHandler)
                .build();
        // 자체 재시도를 두지 않는다(maxRetries=0 = 단일 시도). 재시도는 과부하 때 실패를 되먹여
        // congestion collapse 를 키우는 증폭기였다(측정 확인). 근본(본문 굶음)을 고쳤으므로 재시도로 가릴 실패가 없고,
        // 남는 드문 일시적 실패는 InputModerationClient 의 failurePolicy(CLOSED→503)로 빠르게 표면화한다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        log.info("[OpenAI HTTP] moderation model: blocking JDK HttpClient (HTTP/1.1), 자체 재시도 없음");
        return new OpenAiModerationModel(api, new RetryTemplate(noRetry));
    }
}
