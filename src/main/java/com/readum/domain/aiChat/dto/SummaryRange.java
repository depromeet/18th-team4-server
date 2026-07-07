package com.readum.domain.aiChat.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 요약 구간 선택기의 결과. 이번 회차에 요약할 원문(시간 오름차순)과, 요약 성공 시 요약 반영 지점이 전진할 마지막 메시지 id.
 * 요약할 구간이 없으면 {@link #none()}({@link #isEmpty()} true).
 */
public record SummaryRange(
        List<AiChatMessage> messagesToSummarize,
        Long lastSummarizedMessageId
) {

    private static final SummaryRange NONE = new SummaryRange(List.of(), null);

    /** 요약할 구간 없음. */
    public static SummaryRange none() {
        return NONE;
    }

    /** 요약할 원문 묶음(오름차순)으로부터 구간을 만든다. 마지막 원소의 id 가 요약 반영 지점이 된다. */
    public static SummaryRange of(List<AiChatMessage> messagesToSummarize) {
        return new SummaryRange(
                messagesToSummarize,
                messagesToSummarize.get(messagesToSummarize.size() - 1).getId());
    }

    public boolean isEmpty() {
        return messagesToSummarize.isEmpty();
    }
}
