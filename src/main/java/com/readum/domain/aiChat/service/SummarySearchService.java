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
        // 재생성 중에는 직전 감상문이 있어도 409 를 반환해 폴링 계약(생성 요청 → 폴링 → 완료/실패)을 유지한다.
        if (session.isLocked()) {
            throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        }

        Summary summary = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(sessionId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SUMMARY_NOT_FOUND));

        return switch (summary.getStatus()) {
            case COMPLETED -> SummaryResult.from(summary);
            case FAILED -> throw new ConflictException(AiChatErrorCode.SUMMARY_GENERATION_FAILED);
            // 과도기 분기: Status.IN_PROGRESS 는 다음 Task 에서 enum 과 함께 제거된다. 새 코드는 이 행을 만들지 않는다.
            case IN_PROGRESS -> throw new ConflictException(AiChatErrorCode.SUMMARY_IN_PROGRESS);
        };
    }
}
