package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiChatSessionCreateService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final AiChatSessionRepository aiChatSessionRepository;

    @Transactional
    public AiChatSessionCreateResult execute(AiChatSessionCreateCommand command) {
        User user = userRepository.findBySessionId(command.userSessionId())
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        userBookRepository.findByIdAndUserId(command.userBookId(), user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        AiChatSession saved = aiChatSessionRepository.save(AiChatSession.create(command.userBookId()));
        return AiChatSessionCreateResult.from(saved);
    }
}
