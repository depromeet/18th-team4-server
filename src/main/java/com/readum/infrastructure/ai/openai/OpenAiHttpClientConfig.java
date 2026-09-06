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

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 입력 moderation 클라이언트 구성.
 * 단순 요청/응답이라 리액티브가 불필요 → 블로킹 JDK HttpClient 로 구성하고,
 * 요청의 가상 스레드에서 끝까지 블로킹으로 기다린다 — 요청 본문 쓰기와 응답 대기가
 * 호출 스레드 하나에서 끝나므로 공유 풀에 의존하지 않는다.
 */
@Slf4j
@Configuration
public class OpenAiHttpClientConfig {

    /**
     * moderation 호출의 연결 상한. 한 턴의 선행 처리 여유
     * ({@code ai-chat.streaming.prepare-allowance-seconds}) 안에 들어야 하는 값이라
     * 상수로 노출해 기동 시 대조한다({@code AiChatTimeBudgetValidator}).
     */
    public static final Duration MODERATION_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** moderation 호출의 응답 읽기 상한. 위와 같은 이유로 노출한다. */
    public static final Duration MODERATION_READ_TIMEOUT = Duration.ofSeconds(4);

    /**
     * moderation 호출 하나가 쓸 수 있는 최대 시간 — 연결 + 읽기. 선행 처리 여유가 이 값보다 커야 한다.
     * 두 값을 따로 더하는 곳이 생기지 않도록 여기 한 번만 더해 둔다.
     *
     * <p>연결 2초 + 읽기 4초 = 6초다. 3 + 5 = 8초에서 내렸다 — 선행 처리 여유 10초에는 moderation 말고도
     * 폭주 가드와 전역 게이트의 Redis 호출 둘이 들어가고, Redis 명령 기한을 1초로 못박으면서
     * 그 둘이 최악 2초를 쓰게 됐기 때문이다. 6 + 2 = 8초라 DB(이력 조회·예약·USER 저장) 몫 2초가 남는다.
     * OpenAI moderation 은 짧은 문자열 하나를 판정하는 단순 호출이라 정상 응답이 4초를 넘을 이유가 없다
     * (계산이며 실측 아님). 이 관계는 기동 시 {@code AiChatTimeBudgetValidator} 가 대조한다.
     */
    public static Duration moderationHttpCeiling() {
        return MODERATION_CONNECT_TIMEOUT.plus(MODERATION_READ_TIMEOUT);
    }

    /**
     * moderation ModerationModel 을 블로킹 JDK HttpClient(HTTP/1.1) 로 구성한다 (auto-config 대체).
     * 리액터 전송 팩토리로 감싸면 요청 본문 쓰기가 전역 {@code Schedulers.boundedElastic()} 으로 넘어가
     * 응답 대기와 본문 쓰기가 같은 풀의 서로 다른 워커를 요구하지만, 여기서는 그 분리가 없다 —
     * 본문 쓰기도 응답 대기도 호출한 가상 스레드 하나에서 일어난다.
     * 단순 1회성 호출이라 HTTP/2 멀티플렉싱 이점이 없어 HTTP/1.1 로 고정한다. JDK 자체 연결 풀을 그대로 쓴다.
     */
    @Bean
    @Primary
    public ModerationModel moderationModel(
            ResponseErrorHandler openAiResponseErrorHandler,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl
    ) {
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                // moderation 은 단순 1회성 요청/응답이라 HTTP/2 멀티플렉싱 이점이 없다. HTTP/1.1 로 고정해
                // HTTP/2 스택(스트림 멀티플렉싱·흐름 제어)을 아예 배제 → 가드레일 경로를 단순·예측가능하게 둔다.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(MODERATION_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(MODERATION_READ_TIMEOUT); // moderation 이 매달리지 않게 상한(② read 타임아웃)
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
        log.info("[OpenAI HTTP] moderation model: 블로킹 JDK HttpClient (HTTP/1.1), 자체 재시도 없음, 연결 {}s + 읽기 {}s",
                MODERATION_CONNECT_TIMEOUT.toSeconds(), MODERATION_READ_TIMEOUT.toSeconds());
        return new OpenAiModerationModel(api, new RetryTemplate(noRetry));
    }
}
