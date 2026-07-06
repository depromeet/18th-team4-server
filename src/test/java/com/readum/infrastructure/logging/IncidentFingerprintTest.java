package com.readum.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentFingerprintTest {

    @Test
    void 예외가_있으면_예외클래스와_첫_readum_프레임으로_요약한다() {
        IllegalStateException exception = new IllegalStateException("적재 실패");

        String fingerprint = IncidentFingerprint.of(errorEvent("적재 실패", null, exception));

        // 이 테스트 클래스(com.readum.*)에서 예외를 만들었으므로 첫 애플리케이션 프레임은 이 테스트 메서드다
        assertThat(fingerprint).startsWith("IllegalStateException @ IncidentFingerprintTest.");
    }

    @Test
    void 바깥_예외에_readum_프레임이_없으면_cause_사슬에서_찾는다() {
        IllegalStateException inner = new IllegalStateException("원인");
        RuntimeException outer = new RuntimeException("감싼 예외", inner);
        outer.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.springframework.SomeFramework", "invoke", "SomeFramework.java", 10)
        });

        String fingerprint = IncidentFingerprint.of(errorEvent("실패", null, outer));

        assertThat(fingerprint).startsWith("RuntimeException @ IncidentFingerprintTest.");
    }

    @Test
    void readum_프레임이_전혀_없으면_logger_이름으로_대신한다() {
        IllegalStateException exception = new IllegalStateException("실패");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.springframework.SomeFramework", "invoke", "SomeFramework.java", 10)
        });

        String fingerprint = IncidentFingerprint.of(errorEvent("실패", null, exception));

        assertThat(fingerprint).isEqualTo("IllegalStateException @ SomeLogger");
    }

    @Test
    void 예외가_없으면_logger_와_포맷_전_메시지_템플릿을_쓴다() {
        ILoggingEvent event = errorEvent("summary_job {} 적재 실패", new Object[]{42L}, null);

        String fingerprint = IncidentFingerprint.of(event);

        // 가변 인자(42)가 아니라 자리표시자({}) 원문이 키가 된다
        assertThat(fingerprint).isEqualTo("SomeLogger | summary_job {} 적재 실패");
    }

    private ILoggingEvent errorEvent(String message, Object[] arguments, Throwable throwable) {
        LoggerContext loggerContext = new LoggerContext();
        Logger logger = loggerContext.getLogger("com.readum.test.SomeLogger");
        return new LoggingEvent("", logger, Level.ERROR, message, throwable, arguments);
    }
}
