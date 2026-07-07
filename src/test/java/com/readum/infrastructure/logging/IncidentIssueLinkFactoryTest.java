package com.readum.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentIssueLinkFactoryTest {

    private final IncidentIssueLinkFactory factory =
            new IncidentIssueLinkFactory("https://github.com/depromeet/18th-team4-server", "dev", "abc1234");

    @Test
    void 링크는_error_라벨이_붙은_이슈_생성_화면을_가리킨다() {
        String url = factory.create(errorEvent("적재 실패", null), "fp");

        assertThat(url).startsWith("https://github.com/depromeet/18th-team4-server/issues/new?labels=error");
        assertThat(url).contains("&title=");
        assertThat(url).contains("&body=");
    }

    @Test
    void 본문에_배포_커밋_마커와_장애_정보가_들어간다() {
        String body = decodedBody(factory.create(errorEvent("적재 실패", new IllegalStateException("원인")), "fp"));

        assertThat(body).contains("<!-- deploy-sha: abc1234 -->");
        assertThat(body).contains("- 배포 커밋: `abc1234`");
        assertThat(body).contains("- 환경: `dev`");
        assertThat(body).contains("- fingerprint: `fp`");
        assertThat(body).contains("IllegalStateException");
    }

    @Test
    void 예외가_없으면_stacktrace_자리에_예외_없음을_적는다() {
        String body = decodedBody(factory.create(errorEvent("적재 실패", null), "fp"));

        assertThat(body).contains("(예외 없음)");
    }

    @Test
    void 민감정보는_본문에서_마스킹된다() {
        String body = decodedBody(factory.create(
                errorEvent("호출 실패 url=https://api.example.com?token=secret123", null), "fp"));

        assertThat(body).contains("token=***");
        assertThat(body).doesNotContain("secret123");
    }

    @Test
    void stacktrace_가_아무리_길어도_url_은_상한을_넘지_않는다() {
        IllegalStateException exception = new IllegalStateException("아주 긴 예외 메시지 ".repeat(500));

        String url = factory.create(errorEvent("실패", exception), "fp");

        assertThat(url.length()).isLessThanOrEqualTo(6500);
    }

    @Test
    void 제목은_80자에서_절단된다() {
        String url = factory.create(errorEvent("실패", null), "긴 fingerprint ".repeat(20));

        String title = decodedParameter(url, "title");
        assertThat(title.length()).isLessThanOrEqualTo(80);
        assertThat(title).startsWith("[장애] ");
    }

    private String decodedBody(String url) {
        return decodedParameter(url, "body");
    }

    private String decodedParameter(String url, String name) {
        for (String parameter : url.substring(url.indexOf('?') + 1).split("&")) {
            if (parameter.startsWith(name + "=")) {
                return URLDecoder.decode(parameter.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " 파라미터가 없다: " + url);
    }

    private ILoggingEvent errorEvent(String message, Throwable throwable) {
        LoggerContext loggerContext = new LoggerContext();
        loggerContext.setMDCAdapter(new LogbackMDCAdapter()); // getMDCPropertyMap() 이 어댑터를 요구한다
        Logger logger = loggerContext.getLogger("com.readum.test.SomeLogger");
        return new LoggingEvent("", logger, Level.ERROR, message, throwable, null);
    }
}
