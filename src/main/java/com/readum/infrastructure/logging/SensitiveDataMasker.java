package com.readum.infrastructure.logging;

/**
 * 로그 문자열 안의 민감 값(토큰, Authorization 헤더, API 키)을 마스킹한다.
 *
 * <p>Slack 알림 본문(장애 필드·메시지·stacktrace)에 실려 외부(Slack 채널)로 나가는
 * 텍스트이므로 반드시 이 마스킹을 거친다.
 */
final class SensitiveDataMasker {

    private SensitiveDataMasker() {
    }

    static String mask(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("Bearer ", "Bearer ***")
                .replaceAll("(?i)(authorization:)[^\\n\\r]+", "$1 ***")
                .replaceAll("(?i)(api[_-]?key=)[^\\s&]+", "$1***")
                .replaceAll("(?i)(token=)[^\\s&]+", "$1***");
    }
}
