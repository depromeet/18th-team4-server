package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 중복 판정은 이 인터페이스의 조회가 아니라 <b>삽입 시 유일 위반</b>으로 한다
 * (uk_ai_chat_turn_request_user_request). 조회 후 삽입으로 나누면 같은 ID 의 동시 요청이 모두 통과한다.
 *
 * <p>만료 복구(별도 작업)가 쓸 "기한 지난 미종료 행 목록 조회" 는 그 작업에서 더한다 —
 * 복구도 한 건씩은 아래 {@link #findByIdForUpdate} 로 잠그고 상태를 다시 확인한 뒤 끝낸다.
 */
public interface AiChatTurnRequestRepository extends JpaRepository<AiChatTurnRequest, Long> {

    /** 현재 운영 경로에서는 미사용 — 통합 테스트가 커밋 경계 밖에서 요청 기록 상태를 단언하는 용도. */
    Optional<AiChatTurnRequest> findByUserIdAndRequestId(Long userId, String requestId);

    /**
     * 요청 종료(성공 확정·실패 기록·만료 복구)가 잠글 행을 읽는다 — {@code SELECT ... FOR UPDATE}.
     *
     * <p>잠금이 필요한 이유: 종료는 "미종료인지 확인" 과 "종료 상태로 바꾸고 예산을 되돌린다" 두 단계이고,
     * 그 사이에 다른 실행(늦은 성공 vs 만료 복구)이 같은 행을 보면 둘 다 미종료로 읽어 저장·정산·환불이
     * 두 번 반영된다. 상태 컬럼의 조건부 UPDATE 만으로는 답변 저장·정산까지 한 결정 아래 묶지 못한다.
     *
     * <p>잠금은 트랜잭션이 끝날 때 풀리므로 이 메서드는 반드시 {@code @Transactional} 안에서 부른다.
     * 잠금 대기 시간은 DB 설정(innodb_lock_wait_timeout)을 따르며 여기서 따로 걸지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT aiChatTurnRequest
            FROM AiChatTurnRequest aiChatTurnRequest
            WHERE aiChatTurnRequest.id = :turnRequestId
            """)
    Optional<AiChatTurnRequest> findByIdForUpdate(@Param("turnRequestId") Long turnRequestId);
}
