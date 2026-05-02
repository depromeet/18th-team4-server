package com.readum.domain.aiChat.history;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatHistoryBuilder {

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatProperties aiChatProperties;

    /**
     * 세션의 이전 이력만(현재 진행 중인 user 메시지 제외) LLM 입력 형식으로 변환한다.
     * 현재 user 메시지는 호출자가 결과 리스트 끝에 직접 append 한다.
     */
    public List<HistoryMessage> buildPreviousHistory(Long sessionId) {
        int maxMessages = aiChatProperties.contextWindow().maxMessages();

        List<AiChatMessage> recentDesc = aiChatMessageRepository.findRecentForContextWindow(
                sessionId,
                PageRequest.of(0, maxMessages)
        );

        List<AiChatMessage> chronological = new ArrayList<>(recentDesc);
        Collections.reverse(chronological);

        List<HistoryMessage> history = new ArrayList<>(chronological.size());
        for (AiChatMessage message : chronological) {
            history.add(new HistoryMessage(toHistoryRole(message.getRole()), message.getContent()));
        }
        return history;
    }

    private HistoryMessage.Role toHistoryRole(AiChatMessage.Role role) {
        return switch (role) {
            case USER -> HistoryMessage.Role.USER;
            case ASSISTANT -> HistoryMessage.Role.ASSISTANT;
            case SYSTEM -> throw new IllegalStateException("SYSTEM 메시지는 컨텍스트 윈도우에서 제외되어야 합니다.");
        };
    }
}
