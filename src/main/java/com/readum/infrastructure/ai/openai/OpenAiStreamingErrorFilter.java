package com.readum.infrastructure.ai.openai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * 스트리밍(WebClient) 경로의 오류를 단발 호출과 <b>같은 기준</b>으로 분류한다.
 *
 * <p><b>왜 필요한가.</b> Spring AI 의 {@code OpenAiApi} 는 우리가 준 오류 핸들러를 {@code RestClient} 에만
 * 연결하고 {@code WebClient} 에는 base URL 과 기본 헤더만 얹는다(2.0.0-M4 바이트코드 확인). 그래서
 * 채팅 스트림의 429·401·503 은 우리 분류를 거치지 않고 {@code WebClientResponseException} 으로 올라왔고,
 * 그 타입에는 분류 규칙이 없어 <b>"세지 않는 실패" 로 묻혔다</b> — 결제가 막혀도, 키가 틀려도,
 * 공급자가 과부하라고 말해도 채팅의 장애 상태에는 아무것도 쌓이지 않았다는 뜻이다.
 * 사용자가 가장 많이 쓰는 경로에서 보호가 통째로 빠져 있었던 셈이다.
 *
 * <p><b>이 필터는 Redis 를 부르지 않는다.</b> 이 필터가 도는 스레드는 reactor-netty 의 이벤트 루프라,
 * 그 스레드에서 Redis 를 기다리면 같은 루프에 얹힌 다른 연결의 입출력이 함께 멈춘다. 분류 결과를 오류
 * 신호로 바꿔 올려보내면 그 신호를 받은 보호 계층({@code AiProviderCallGuard})이 기록을 가상 스레드로
 * 넘겨 남기므로, 여기서는 아무 상태도 적지 않는다.
 */
@Slf4j
public class OpenAiStreamingErrorFilter {

    private OpenAiStreamingErrorFilter() {
    }

    /**
     * 오류 응답을 본문까지 읽어 분류한 뒤 오류 신호로 바꾼다.
     *
     * <p>본문을 읽는 이유: 같은 429 안에 "속도를 줄여라" 와 "결제가 막혔다" 가 섞여 있고 그 구분이
     * {@code error.code} 에만 있다. 본문을 읽지 않으면 둘을 나눌 수 없다. 오류 응답은 스트림이 아니라
     * 짧은 JSON 한 덩이라 통째로 읽어도 안전하다.
     */
    public static ExchangeFilterFunction of(OpenAiErrorResponseTranslator translator) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (!response.statusCode().isError()) {
                return Mono.just(response);
            }
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> translator.translate(
                            response.statusCode(), response.headers().asHttpHeaders(), body))
                    .flatMap(translation -> Mono.<ClientResponse>error(translation.failure()));
        });
    }
}
