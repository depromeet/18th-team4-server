package com.readum.infrastructure.logging;

/**
 * 로그 문자열 안의 민감 값(토큰, Authorization 헤더, API 키)을 마스킹한다.
 *
 * <p>Slack 알림 본문과 분석 이슈 프리필 링크 본문이 함께 사용한다. 두 곳 모두
 * 외부(Slack 채널, GitHub 이슈)로 나가는 텍스트이므로 반드시 이 마스킹을 거친다.
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
