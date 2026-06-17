package com.readum.domain.summary.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * 배치 제공자에 보낼 단위 요청 항목.
 * customId 로 결과를 다시 작업과 연결한다.
 */
public record SummaryBatchRequestItem(
        String customId,
        List<AiChatMessage> messages
) {}
