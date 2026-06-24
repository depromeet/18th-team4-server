package com.readum.logprocessor.core;

import java.util.regex.Pattern;

/**
 * fingerprint 계산 전용: 메시지에서 매번 달라지는 값(UUID·숫자)을 placeholder 로 바꿔
 * 같은 원인의 에러가 같은 정규화 결과를 갖게 한다.
 * UUID 를 숫자보다 먼저 치환한다(UUID 내부 숫자가 먼저 먹히지 않도록).
 */
public final class ErrorMessageNormalizer {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    public String normalize(String message) {
        if (message == null) {
            return "";
        }
        String result = UUID.matcher(message).replaceAll("{uuid}");
        result = NUMBER.matcher(result).replaceAll("{number}");
        return result;
    }
}
