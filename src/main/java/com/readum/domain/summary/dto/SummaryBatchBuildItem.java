package com.readum.domain.summary.dto;

import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

/**
 * builder 가 BATCH 청크 선점 단계에서 만든다.
 * SYNC 경로의 SummaryGenerationContext(3-인자)와 분리해 각 경로를 독립적으로 바꿀 수 있도록 전용 record 로 뒀다.
 */
public record SummaryBatchBuildItem(
        Long jobId,
        Long sessionId,
        Long userBookId,
        List<AiChatMessage> messages
) {}
