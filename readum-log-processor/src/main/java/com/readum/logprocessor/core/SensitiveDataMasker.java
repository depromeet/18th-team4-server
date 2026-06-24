package com.readum.logprocessor.core;

/**
 * 로그/스택트레이스/요청 정보에서 secret·PII 를 가린다.
 * Slack·Claude·GitHub Issue·DB 로 나가기 전 항상 통과시킨다.
 * 규칙은 기존 SlackWebhookAppender.escape() 와 정렬(이메일 추가).
 */
public final class SensitiveDataMasker {

    public String mask(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replaceAll("(?i)Bearer\\s+[^\\s\\n\\r]+", "Bearer ***")
                .replaceAll("(?i)(authorization:)[^\\n\\r]+", "$1 ***")
                .replaceAll("(?i)(api[_-]?key=)[^\\s&]+", "$1***")
                .replaceAll("(?i)(token=)[^\\s&]+", "$1***")
                .replaceAll("(?i)(cookie:)[^\\n\\r]+", "$1 ***")
                .replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.-]+", "{email}");
    }
}
