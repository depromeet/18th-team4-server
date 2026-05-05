package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiChatHistorySearchService {

    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatProperties aiChatProperties;

    /**
     * 세션의 이전 이력만(현재 진행 중인 user 메시지 제외) LLM 입력 형식으로 변환한다.
     * 현재 user 메시지는 호출자가 결과 리스트 끝에 직접 append 한다.
     * repository 가 최신순(DESC) 으로 반환하므로 역순 순회로 시간순(ASC) 리스트를 만든다.
     */
    public List<HistoryMessage> findPreviousHistory(Long sessionId) {
        int maxMessages = aiChatProperties.contextWindow().maxMessages();

        List<AiChatMessage> recentDesc = aiChatMessageRepository.findRecentForContextWindow(
                sessionId,
                PageRequest.of(0, maxMessages)
        );

        List<HistoryMessage> history = new ArrayList<>(recentDesc.size());
        for (int i = recentDesc.size() - 1; i >= 0; i--) {
            history.add(HistoryMessage.from(recentDesc.get(i)));
        }
        return history;
    }
}
