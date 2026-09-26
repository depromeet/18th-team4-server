package com.readum.infrastructure.ai.openai;

import com.readum.infrastructure.ai.openai.availability.AiAvailabilityProperties;
import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import tools.jackson.databind.ObjectMapper;

/**
 * 프로젝트마다 오류 분류를 하나씩 만든다 — 단발 호출용 핸들러와 스트리밍용 필터 두 모양으로.
 *
 * <p>분류는 어느 프로젝트에서 난 오류인지를 로그와 예외 메시지에 담는다. 공용 하나를 다섯 경로가 나눠 쓰면
 * 그 표시가 한 프로젝트로 고정되어, 제목 생성에서 난 오류를 운영에서 채팅 오류로 읽게 된다.
 * 그래서 호출 경로를 구성하는 자리에서 그 경로의 프로젝트로 만들어 붙인다.
 *
 * <p><b>두 모양이 필요한 이유</b>는 Spring AI 의 {@code OpenAiApi} 가 오류 핸들러를 {@code RestClient} 에만
 * 연결하기 때문이다 — 스트리밍이 쓰는 {@code WebClient} 에는 붙지 않아, 같은 분류를 필터로 따로 얹어야
 * 채팅 스트림의 429·401·503 이 같은 기준으로 집계된다.
 */
@Component
public class OpenAiResponseErrorHandlerFactory {

    private final ObjectMapper objectMapper;
    private final AiAvailabilityProperties availabilityProperties;

    public OpenAiResponseErrorHandlerFactory(
            ObjectMapper objectMapper,
            AiAvailabilityProperties availabilityProperties
    ) {
        this.objectMapper = objectMapper;
        this.availabilityProperties = availabilityProperties;
    }

    /** 단발 호출(RestClient) 경로에 붙일 핸들러. */
    public ResponseErrorHandler create(OpenAiProject project) {
        return new OpenAiResponseErrorHandler(
                objectMapper, project, availabilityProperties.authBlockSeconds());
    }

    /** 스트리밍(WebClient) 경로에 붙일 필터 — 단발 호출과 같은 분류를 쓴다. */
    public ExchangeFilterFunction createStreamingErrorFilter(OpenAiProject project) {
        return OpenAiStreamingErrorFilter.of(translator(project));
    }

    private OpenAiErrorResponseTranslator translator(OpenAiProject project) {
        return new OpenAiErrorResponseTranslator(
                objectMapper, project, availabilityProperties.authBlockSeconds());
    }
}
