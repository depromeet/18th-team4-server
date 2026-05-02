package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.MessageListCommand;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.MessageResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiChatMessageSearchService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;

    @Transactional(readOnly = true)
    public MessageListResult findBySessionId(MessageListCommand command) {
        aiChatSessionRepository.findByIdAndOwner(command.sessionId(), command.userId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        int pageIndex = Math.max(0, command.page() - 1);
        Page<AiChatMessage> page = aiChatMessageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(
                command.sessionId(),
                PageRequest.of(pageIndex, command.size())
        );
        return new MessageListResult(
                page.getContent().stream().map(MessageResult::from).toList(),
                (int) page.getTotalElements(),
                command.page(),
                command.size(),
                page.hasNext()
        );
    }
}
