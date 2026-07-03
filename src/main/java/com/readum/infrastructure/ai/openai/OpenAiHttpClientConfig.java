package com.readum.infrastructure.ai.openai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.api.OpenAiModerationApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * OpenAI 호출의 두 게이트 경로를 성격에 맞는 클라이언트로 나눈다.
 *  - 채팅 스트리밍: reactor(WebClient) — 진짜 스트리밍이라 논블로킹이 맞다. H2 + idle-evict 적용, 전용 풀.
 *  - 입력 moderation: 단순 요청/응답이라 리액티브가 불필요 → 블로킹 JDK HttpClient 로 구성하고 가상 스레드에서 블로킹한다.
 *    (리액티브로 감싸면 동기 호출이 reactor boundedElastic 스케줄러(기본 10×CPU)로 offload 되어 그 캡에 직렬화된다 — 측정 확인.)
 *
 * 채팅 풀 공통 설정: idle-evict(OpenAI 가 닫기 전에 우리가 먼저 idle 연결 버림 → stale 재사용 차단),
 *           protocol(H2, HTTP11) ALPN 협상(OpenAI=H2, 미지원 서버=HTTP11 fallback).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OpenAiHttpClientProperties.class)
public class OpenAiHttpClientConfig {

    private HttpClient build(String poolName, OpenAiHttpClientProperties props) {
        ConnectionProvider provider = ConnectionProvider.builder(poolName)
                .maxConnections(props.maxConnections())
                .maxIdleTime(props.maxIdleTime())
                .maxLifeTime(props.maxLifeTime())
                .pendingAcquireTimeout(props.pendingAcquireTimeout())
                .evictInBackground(props.maxIdleTime())
                .build();
        HttpClient client = HttpClient.create(provider);
        if (props.http2()) {
            client = client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11);
        }
        return client;
    }

    /** 채팅 스트리밍 전용 H2 풀. */
    @Bean
    public HttpClient openAiChatHttpClient(OpenAiHttpClientProperties props) {
        log.info("[OpenAI HTTP] chat pool: http2={}, maxConnections={}, maxIdleTime={}, maxLifeTime={}",
                props.http2(), props.maxConnections(), props.maxIdleTime(), props.maxLifeTime());
        return build("openai-chat", props);
    }

    @Bean
    public WebClientCustomizer openAiWebClientConnectionCustomizer(HttpClient openAiChatHttpClient) {
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(openAiChatHttpClient));
    }

    /**
     * moderation ModerationModel 을 블로킹 JDK HttpClient(HTTP/1.1) 로 구성 (auto-config 대체).
     * reactor 클라이언트로 감싸지 않으므로 boundedElastic offload·그 캡(10×CPU)이 없다 — 가상 스레드에서 그냥 블로킹.
     * 단순 1회성 호출이라 H2 멀티플렉싱 이점이 없어 HTTP/1.1 로 고정(H2 스택 배제). JDK 자체 연결 풀이라 채팅 reactor 풀과 자연히 분리된다.
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
