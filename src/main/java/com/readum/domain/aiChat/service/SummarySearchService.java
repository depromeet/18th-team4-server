package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SummarySearchService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryRepository summaryRepository;

    public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        // "생성 중" 은 감상문 행이 아니라 세션 잠금 상태가 표현한다.
        // 재생성 중에는 직전 감상문이 있어도 409 를 반환해 폴링 계약(생성 요청 → 폴링 → 완료)을 유지한다.
        if (session.isLocked()) {
            throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        }

        // 감상문은 성공 기록만 남는다(실패 시 행 없음). 최신 행이 있으면 그게 "현재 감상문", 없으면 404.
        Summary summary = summaryRepository.findFirstByAiChatSessionIdOrderByCreatedAtDescIdDesc(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

        return SummaryResult.from(summary);
    }
}
