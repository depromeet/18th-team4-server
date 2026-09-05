package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 중복 판정은 이 인터페이스의 조회가 아니라 <b>삽입 시 유일 위반</b>으로 한다
 * (uk_ai_chat_turn_request_user_request). 조회 후 삽입으로 나누면 같은 ID 의 동시 요청이 모두 통과한다.
 *
 * <p>만료 복구(별도 작업)가 쓸 "기한 지난 미종료 행 조회 + 행 잠금" 은 그 작업에서 더한다.
 */
public interface AiChatTurnRequestRepository extends JpaRepository<AiChatTurnRequest, Long> {

    /** 현재 운영 경로에서는 미사용 — 통합 테스트가 커밋 경계 밖에서 요청 기록 상태를 단언하는 용도. */
    Optional<AiChatTurnRequest> findByUserIdAndRequestId(Long userId, String requestId);
}
