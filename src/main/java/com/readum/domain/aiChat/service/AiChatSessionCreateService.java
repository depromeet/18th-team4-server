package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiChatSessionCreateService {

    private final UserBookRepository userBookRepository;
    private final AiChatSessionRepository aiChatSessionRepository;

    @Transactional
    public AiChatSessionCreateResult execute(AiChatSessionCreateCommand command) {
        userBookRepository.findByIdAndUserId(command.userBookId(), command.userId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        AiChatSession saved = aiChatSessionRepository.save(AiChatSession.create(command.userBookId()));
        return new AiChatSessionCreateResult(saved.getId());
    }
}
