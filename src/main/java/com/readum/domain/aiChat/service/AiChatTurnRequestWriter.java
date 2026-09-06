package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatTurnRequest;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 요청 기록(ai_chat_turn_request)의 원자적 DB 쓰기 구간 — 선행 단계가 쓰는 전이만 담는다.
 * 선언적 {@code @Transactional} 협력자 빈 (transaction.md — 외부 호출이 섞인 서비스 흐름에서 DB 쓰기만 분리).
 *
 * <p>요청 <b>종료</b>(성공 확정·청구 없는 종료·만료 복구)는 이 클래스가 아니라
 * {@link AiChatTurnOutcomeWriter} 가 맡는다. 선행 단계의 거절도 그쪽의
 * {@code finishWithoutCharge} 한 번으로 끝낸다 — 상태 전이와 예약 반환이 갈리지 않게 하기 위해서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AiChatTurnRequestWriter {

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository;
    private final UserTokenBudgetWriter userTokenBudgetWriter;

    /**
     * 요청 자리를 잡는다 — 중복 판정이 곧 이 삽입이다.
     * 이미 같은 (userId, requestId) 가 있으면 UNIQUE 위반으로 {@code DataIntegrityViolationException} 이
     * 이 트랜잭션 밖으로 나간다. 안에서 잡아 결과값으로 바꾸면 트랜잭션이 rollback-only 로 표시돼
     * 커밋 시점에 다시 터지므로, 판정은 <b>호출부</b>가 예외로 받아 중복 응답으로 바꾼다.
     */
    @Transactional
    Long claim(Long userId, Long sessionId, String requestId, Duration expiryTimeout) {
        AiChatTurnRequest claimed = aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, sessionId, requestId, expiryTimeout));
        return claimed.getId();
    }

    /**
     * 예산 예약과 예약 정보 기록을 <b>한 트랜잭션</b>으로 커밋한다.
     * {@link UserTokenBudgetWriter#reserve} 는 기본 전파라 이 트랜잭션에 합류한다 — 원장 증가와
     * 요청 행의 예약 정보가 함께 커밋되거나 함께 롤백된다. 둘이 갈리면 되돌릴 양을 모르는
     * 예약(반환 불가) 이나 없는 예약의 반환(과다 환급)이 생긴다.
     * 거절(Denied)이면 예약이 없으므로 요청 행은 ACCEPTED 그대로 둔다.
     */
    @Transactional
    UserTokenBudgetWriter.ReserveResult reserveWithRecord(Long turnRequestId, Long userId, int reservedTokens) {
        UserTokenBudgetWriter.ReserveResult reserveResult = userTokenBudgetWriter.reserve(userId, reservedTokens);
        if (reserveResult instanceof UserTokenBudgetWriter.ReserveResult.Granted granted) {
            find(turnRequestId).markReserved(granted.periodKey(), granted.reservedTokens());
        }
        return reserveResult;
    }

    private AiChatTurnRequest find(Long turnRequestId) {
        return aiChatTurnRequestRepository.findById(turnRequestId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.TURN_REQUEST_NOT_FOUND));
    }
}
