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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiChatMessageSearchService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;

    public MessageListResult findBySessionId(MessageListCommand command) {
        aiChatSessionRepository.findByIdAndOwner(command.sessionId(), command.userId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        int pageIndex = Math.max(0, command.page() - 1);
        Slice<AiChatMessage> slice = aiChatMessageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(
                command.sessionId(),
                PageRequest.of(pageIndex, command.size())
        );
        return new MessageListResult(
                slice.getContent().stream().map(MessageResult::from).toList(),
                command.page(),
                command.size(),
                slice.hasNext()
        );
    }
}
