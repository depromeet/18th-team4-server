package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.model.aiChat.entity.AiChatMessage;

import java.util.List;

public interface AiSummaryClient {

    SummaryDraftResult generate(List<AiChatMessage> messages);
}
