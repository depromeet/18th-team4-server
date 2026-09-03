package com.readum.infrastructure.ai.openai.bench;

import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.guardrail.GuardrailProperties;
import com.readum.infrastructure.ai.openai.moderation.OpenAiInputModerationClientImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.api.OpenAiModerationApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * bench-bridge 프로파일 전용 실험 구성 — 평상시 기동에는 존재하지 않는다.
 *
 * 목적: "동기 moderation 호출 밑에 리액터 전송 브리지(ReactorClientHttpRequestFactory)가 깔리고,
 * 그 호출을 subscribeOn(boundedElastic) 으로 offload 한 구조"가 부하에서 자기 교착으로 붕괴함을
 * 실제 서비스 스택(Spring AI OpenAiModerationApi + 실제 분류 로직) 안에서 재현하기 위한 구성.
 *
 * 기존 moderation 빈({@code OpenAiHttpClientConfig})은 오버라이드하지 않는다 — 이 구성은 자기 전용
 * ModerationModel 과 InputModerationClient 를 별도 이름의 빈으로 조립하며, defaultCandidate=false 로
 * 선언해 {@code @Qualifier("benchBridgeInputModerationClient")} 로만 주입된다.
 */
@Slf4j
@Configuration
@Profile("bench-bridge")
public class BenchBridgeModerationConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean(defaultCandidate = false)
    public InputModerationClient benchBridgeInputModerationClient(
            ResponseErrorHandler openAiResponseErrorHandler,
            GuardrailProperties guardrailProperties,
            @Value("${bench.bridge.base-url:http://127.0.0.1:9099}") String baseUrl,
            @Value("${bench.bridge.api-key:bench-key}") String apiKey
    ) {
        // 재현의 심장: 기본 생성자로 만들고 setExecutor(...) 를 호출하지 않는다.
        // 그래야 블로킹 요청의 본문 쓰기(OutputStreamPublisher)가 기본값인 전역
        // Schedulers.boundedElastic() 으로 제출된다 — 워커 전원이 응답 대기로 블록되면
        // 본문 쓰기 작업이 실행될 스레드가 없어 자기 교착이 된다(격리 마이크로벤치에서 확정한 메커니즘).
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        OpenAiModerationApi moderationApi = OpenAiModerationApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(openAiResponseErrorHandler)
                .build();

        // 재시도 0(단일 시도) — 기존 moderation 빈과 동일 정책. 재시도가 실패를 증폭해 측정을 흐리지 않게 한다.
        RetryPolicy noRetry = RetryPolicy.builder().maxRetries(0).build();
        OpenAiModerationModel moderationModel = new OpenAiModerationModel(moderationApi, new RetryTemplate(noRetry));

        log.info("[bench-bridge] moderation model: ReactorClientHttpRequestFactory (executor 미지정 → 본문 쓰기 boundedElastic), base-url={}", baseUrl);
        // 실제 서비스의 분류 로직(카테고리 화이트리스트·failurePolicy)을 그대로 통과시키기 위해
        // 실제 GuardrailProperties 빈으로 실제 구현체를 직접 조립한다.
        return new OpenAiInputModerationClientImpl(moderationModel, guardrailProperties);
    }
}
