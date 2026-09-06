package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 중복 판정은 이 인터페이스의 조회가 아니라 <b>삽입 시 유일 위반</b>으로 한다
 * (uk_ai_chat_turn_request_user_request). 조회 후 삽입으로 나누면 같은 ID 의 동시 요청이 모두 통과한다.
 *
 * <p>만료 복구는 {@link #findOverdueUnfinishedIds} 로 대상 목록을 훑고, 한 건씩 아래
 * {@link #findByIdForUpdate} 로 잠근 뒤 상태·만료 조건을 다시 확인하고 끝낸다.
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

    /**
     * 만료 복구가 훑을 대상 — 기한이 {@code overdueBefore} 보다 앞선 <b>미종료</b> 요청의 id 목록.
     *
     * <p><b>잠그지 않고 훑는다.</b> 여기서 잠그면 한 번의 스캔이 대상 전부를 스캔이 끝날 때까지 붙들어,
     * 정상적으로 끝나려는 늦은 성공까지 기다리게 만든다. 실제 종료는 한 건씩
     * {@link #findByIdForUpdate} 로 잠근 뒤에 하고, 이 목록은 후보일 뿐이다 —
     * 훑은 뒤 잠그기 전에 다른 실행이 그 행을 끝냈을 수 있으므로 잠금 뒤 상태·만료 조건을 다시 본다.
     *
     * <p>엔티티가 아니라 id 만 돌려주는 이유: 어차피 종료 트랜잭션이 같은 행을 잠금 조회로 다시 읽는다.
     * 여기서 읽은 엔티티를 들고 다니면 잠금 전에 읽은 낡은 상태를 판단에 쓸 위험만 생긴다.
     *
     * <p>정렬은 기한이 이른 순 — 상한(Pageable) 때문에 한 번에 다 처리하지 못할 때 오래 묶여 있던
     * 예약부터 돌려준다. 같은 기한이면 id 순으로 고정해 스캔마다 순서가 흔들리지 않게 한다.
     * 인덱스는 {@code idx_ai_chat_turn_request_recovery (status, expires_at)} 다.
     */
    @Query("""
            SELECT aiChatTurnRequest.id
            FROM AiChatTurnRequest aiChatTurnRequest
            WHERE aiChatTurnRequest.status IN (
                      com.readum.model.aiChat.entity.AiChatTurnRequest.Status.ACCEPTED
                    , com.readum.model.aiChat.entity.AiChatTurnRequest.Status.RESERVED
                  )
              AND aiChatTurnRequest.expiresAt < :overdueBefore
            ORDER BY aiChatTurnRequest.expiresAt ASC
                   , aiChatTurnRequest.id ASC
            """)
    List<Long> findOverdueUnfinishedIds(
            @Param("overdueBefore") LocalDateTime overdueBefore, Pageable pageable);
}
