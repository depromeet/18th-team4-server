package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionListCommand;
import com.readum.domain.aiChat.dto.AiChatSessionListResult;
import com.readum.domain.aiChat.dto.AiChatSessionResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.AiChatSessionListProjection;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiChatSessionSearchService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final UserBookRepository userBookRepository;

    public AiChatSessionListResult findByUserBookId(AiChatSessionListCommand command) {
        User user = userRepository.findBySessionId(command.userSessionId())
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        userBookRepository.findByIdAndUserId(command.userBookId(), user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        int pageIndex = Math.max(0, command.page() - 1);
        Slice<AiChatSessionListProjection> slice = aiChatSessionRepository.findSessionsByUserBookIdAndOwner(
                command.userBookId(),
                user.getId(),
                PageRequest.of(pageIndex, command.size())
        );

        return new AiChatSessionListResult(
                slice.getContent().stream().map(AiChatSessionResult::from).toList(),
                command.page(),
                command.size(),
                slice.hasNext()
        );
    }
}
