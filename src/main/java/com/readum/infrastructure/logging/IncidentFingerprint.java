package com.readum.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/**
 * ERROR 로그 한 건을 "같은 오류인지" 사람이 알아볼 수 있는 짧은 식별 문자열로 요약한다.
 *
 * <p>계산 규칙 (Sentry 계열 오류 추적 도구의 fingerprint 방식을 축소한 것):
 * <ul>
 *   <li>예외가 있으면 — {@code 예외 클래스 단순명 @ 스택에서 첫 com.readum 프레임(클래스.메서드)}.
 *       바깥 예외에 com.readum 프레임이 없으면 cause 사슬을 따라 내려가며 찾는다.</li>
 *   <li>예외가 없으면 — {@code logger 단순명 | 포맷 전 메시지 템플릿}. 템플릿({@code {}} 자리표시자가
 *       치환되기 전 원문)을 쓰므로 ID 같은 가변 인자가 키를 흔들지 않는다.</li>
 * </ul>
 */
final class IncidentFingerprint {

    private static final String APPLICATION_PACKAGE_PREFIX = "com.readum.";

    private IncidentFingerprint() {
    }

    static String of(ILoggingEvent event) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy == null) {
            return simpleName(event.getLoggerName()) + " | " + event.getMessage();
        }
        return simpleName(throwableProxy.getClassName())
                + " @ " + firstApplicationFrame(throwableProxy, event);
    }

    private static String firstApplicationFrame(IThrowableProxy throwableProxy, ILoggingEvent event) {
        for (IThrowableProxy current = throwableProxy; current != null; current = current.getCause()) {
            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            if (frames == null) {
                continue;
            }
            for (StackTraceElementProxy frameProxy : frames) {
                StackTraceElement frame = frameProxy.getStackTraceElement();
                if (frame.getClassName().startsWith(APPLICATION_PACKAGE_PREFIX)) {
                    return simpleName(frame.getClassName()) + "." + frame.getMethodName();
                }
            }
        }
        return simpleName(event.getLoggerName());
    }

    private static String simpleName(String qualifiedName) {
        int lastDotIndex = qualifiedName.lastIndexOf('.');
        return lastDotIndex < 0 ? qualifiedName : qualifiedName.substring(lastDotIndex + 1);
    }
}
