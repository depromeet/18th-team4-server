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
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * [측정용 임시] 문제 구조 재현 — 측정 후 revert.
 * 입력 moderation 클라이언트를 Reactor 전송(ReactorClientHttpRequestFactory)으로 구성한다.
 * executor 를 지정하지 않아 본문 쓰기가 전역 Schedulers.boundedElastic() 에 위임된다 — 재현 핵심.
 */
@Slf4j
@Configuration
public class OpenAiHttpClientConfig {

    /**
     * [측정용 임시] 문제 구조 재현 — 측정 후 revert.
     * moderation ModerationModel 을 동기 RestClient + Reactor 전송 팩토리로 구성한다 (auto-config 대체).
     * 기본 생성자를 쓰고 setExecutor 를 호출하지 않는다 — 기본 executor = 전역 Schedulers.boundedElastic().
     * 본문 쓰기가 전역 boundedElastic 에 위임되어, 동시 요청 ≥ 풀 상한에서 자기 교착이 성립하는지 검증한다.
     */
    @Bean
    @Primary
    public ModerationModel moderationModel(
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl
    ) {
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
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
        log.info("[OpenAI HTTP] moderation model: [측정용 임시] Reactor 전송 팩토리(기본 executor=boundedElastic), 자체 재시도 없음");
        return new OpenAiModerationModel(api, new RetryTemplate(noRetry));
    }
}
