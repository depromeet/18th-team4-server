package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
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
     *
     * <p><b>예산을 건드리기 전에 요청 행부터 잠근다.</b> 접수 뒤 처리가 길게 지연되면(예: 폭주 가드의
     * Redis 응답 지연) 그 사이 만료 복구가 이 요청을 이미 {@code EXPIRED} 로 끝냈을 수 있다.
     * 잠금 없이 예약하면 늦게 재개된 이 실행이 그 종료를 {@code RESERVED} 로 덮어쓰고 생성을 시작한다 —
     * 요청 종료에 적용한 "잠금 → 미종료 확인 → 한 트랜잭션" 보호를 예약 전이에도 그대로 쓴다.
     *
     * <p><b>잠금 순서는 요청 행({@code ai_chat_turn_request}) → 예산 행({@code user_token_budget}) 이다.</b>
     * 종료 트랜잭션({@link AiChatTurnOutcomeWriter})도 같은 순서로 잠그므로 예약과 종료가 서로 교착하지 않는다.
     */
    @Transactional
    UserTokenBudgetWriter.ReserveResult reserveWithRecord(Long turnRequestId, Long userId, int reservedTokens) {
        lockAccepted(turnRequestId);
        UserTokenBudgetWriter.ReserveResult reserveResult = userTokenBudgetWriter.reserve(userId, reservedTokens);
        if (reserveResult instanceof UserTokenBudgetWriter.ReserveResult.Granted granted) {
            // 예약 UPDATE 는 @Modifying(clearAutomatically) 라 실행 뒤 영속성 컨텍스트를 비운다 —
            // 잠글 때 읽어 둔 객체는 준영속이 되므로 상태 전이를 실을 행을 다시 읽는다.
            // 행 잠금은 DB 트랜잭션이 들고 있어 이 재조회 사이에 다른 실행이 끼어들지 못한다.
            find(turnRequestId).markReserved(granted.periodKey(), granted.reservedTokens());
        }
        return reserveResult;
    }

    /**
     * 예약 대상 요청 행을 잠그고, 아직 접수 상태인지 확인한다.
     *
     * <p>종료 상태(SUCCEEDED/FAILED/EXPIRED)면 예산을 건드리지 않고 409 로 거절한다.
     * 중복 재전송과 구분되는 사유 코드를 쓴다 — 운영 로그와 클라이언트가 "같은 식별자의 재전송" 과
     * "접수는 됐지만 기한이 지나 끝난 요청" 을 구분할 수 있어야 한다.
     *
     * <p>{@code RESERVED} 등 그 밖의 상태는 정상 흐름에서 나올 수 없다 — 이 행을 예약하는 실행은
     * 자리를 잡은 그 실행 하나뿐이다. 프로그램 오류로 다룬다.
     */
    private void lockAccepted(Long turnRequestId) {
        AiChatTurnRequest turnRequest = aiChatTurnRequestRepository.findByIdForUpdate(turnRequestId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.TURN_REQUEST_NOT_FOUND));
        if (turnRequest.isTerminal()) {
            log.info("이미 종료된 요청의 예약 거절 turnRequestId={} status={}",
                    turnRequestId, turnRequest.getStatus());
            throw new ConflictException(AiChatErrorCode.TURN_REQUEST_ALREADY_FINISHED);
        }
        if (turnRequest.getStatus() != AiChatTurnRequest.Status.ACCEPTED) {
            throw new IllegalStateException(
                    "접수 상태가 아닌 요청에 예약을 시도했습니다: turnRequestId=" + turnRequestId
                            + " status=" + turnRequest.getStatus());
        }
    }

    private AiChatTurnRequest find(Long turnRequestId) {
        return aiChatTurnRequestRepository.findById(turnRequestId)
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.TURN_REQUEST_NOT_FOUND));
    }
}
