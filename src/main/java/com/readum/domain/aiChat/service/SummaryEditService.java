package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryEditCommand;
import com.readum.domain.aiChat.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SummaryEditService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryRepository summaryRepository;

    @Transactional
    public SummaryResult execute(SummaryEditCommand command) {
        User user = userRepository.findBySessionId(command.userSessionId())
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        aiChatSessionRepository.findByIdAndOwner(command.sessionId(), user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        // 감상문은 성공 기록만 남으므로(write-once, 실패 행 없음) 최신 행이 곧 편집 대상이다.
        Summary summary = summaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(command.sessionId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

        summary.edit(command.title(), command.body());

        return SummaryResult.from(summary);
    }
}
