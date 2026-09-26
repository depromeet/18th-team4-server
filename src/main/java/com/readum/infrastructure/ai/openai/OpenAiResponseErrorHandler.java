package com.readum.infrastructure.ai.openai;

import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 단발 호출(RestClient) 경로의 오류 핸들러. 분류는 {@link OpenAiErrorResponseTranslator} 가 하고,
 * 여기서는 그 결과를 던진다.
 *
 * <p><b>여기서 상태를 적지 않는다.</b> 예전에는 결제·지출 한도 쿨다운을 이 자리에서 Redis 에 직접 적었는데,
 * 같은 오류가 올라가 장애 차단기의 차단도 함께 열어 같은 대상을 막는 장치가 둘이 되었다. 지금은 예외만
 * 올려보내고, 무엇을 얼마나 막을지는 호출을 감싼 보호 계층({@code AiProviderCallGuard})이 한 곳에서 정한다.
 *
 * <p><b>프로젝트마다 하나씩 만든다.</b> 오류 로그와 예외 메시지가 "어느 프로젝트에서 난 오류인지" 를
 * 담아야 하고, 그 짝이 어긋나면 운영에서 원인을 엉뚱한 기능으로 읽는다.
 */
@Slf4j
public class OpenAiResponseErrorHandler implements ResponseErrorHandler {

    private final OpenAiErrorResponseTranslator translator;

    public OpenAiResponseErrorHandler(
            ObjectMapper objectMapper,
            OpenAiProject project,
            long authBlockSeconds
    ) {
        this.translator = new OpenAiErrorResponseTranslator(objectMapper, project, authBlockSeconds);
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, org.springframework.http.HttpMethod method, ClientHttpResponse response)
            throws IOException {
        String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        throw translator.translate(response.getStatusCode(), response.getHeaders(), body).failure();
    }
}
