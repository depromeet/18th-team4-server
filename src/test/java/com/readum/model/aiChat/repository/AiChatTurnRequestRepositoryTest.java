package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatTurnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요청 기록의 유일성·조회 계약을 DB 에 대고 검증한다.
 * 유일성 의미는 스키마의 책임이라, 중복은 조회가 아니라 삽입 시 위반으로 드러나야 한다.
 */
@SpringBootTest
@Transactional
class AiChatTurnRequestRepositoryTest {

    private static final Duration EXPIRY_TIMEOUT = Duration.ofMinutes(3);

    @Autowired
    private AiChatTurnRequestRepository aiChatTurnRequestRepository;

    private static long userSeq = 970_000L;

    private static synchronized long nextUserId() {
        return userSeq++;
    }

    @Test
    void 같은_사용자가_같은_요청_식별자를_다시_삽입하면_유일_제약_위반으로_거절된다() {
        long userId = nextUserId();
        aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 7L, "request-a", EXPIRY_TIMEOUT));

        assertThatThrownBy(() -> aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 7L, "request-a", EXPIRY_TIMEOUT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_사용자라도_다른_세션이면_같은_식별자를_다시_쓸_수_없다() {
        long userId = nextUserId();
        aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 7L, "request-b", EXPIRY_TIMEOUT));

        // 유일 범위는 (사용자, 요청 식별자) 다 — 세션을 바꿔 같은 식별자를 재사용하는 것도 같은 요청으로 본다.
        assertThatThrownBy(() -> aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 8L, "request-b", EXPIRY_TIMEOUT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 사용자가_다르면_같은_요청_식별자도_각각_받아들인다() {
        long firstUserId = nextUserId();
        long secondUserId = nextUserId();
        aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(firstUserId, 7L, "shared-id", EXPIRY_TIMEOUT));

        // 식별자는 클라이언트가 발급하므로 서로 다른 사용자가 같은 문자열을 쓸 수 있다 — 서로를 막으면 안 된다.
        assertThatCode(() -> aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(secondUserId, 9L, "shared-id", EXPIRY_TIMEOUT)))
                .doesNotThrowAnyException();
    }

    @Test
    void 소유자와_요청_식별자로_요청_기록을_찾는다() {
        long userId = nextUserId();
        aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 7L, "request-c", EXPIRY_TIMEOUT));

        AiChatTurnRequest found =
                aiChatTurnRequestRepository.findByUserIdAndRequestId(userId, "request-c").orElseThrow();

        assertThat(found.getSessionId()).isEqualTo(7L);
        assertThat(found.getStatus()).isEqualTo(AiChatTurnRequest.Status.ACCEPTED);
        assertThat(found.getReservedTokens()).isNull();
        assertThat(found.getBudgetPeriodKey()).isNull();
        assertThat(found.getExpiresAt()).isEqualTo(found.getCreatedAt().plus(EXPIRY_TIMEOUT));
    }

    @Test
    void 다른_사용자의_같은_식별자는_조회되지_않는다() {
        long ownerUserId = nextUserId();
        long otherUserId = nextUserId();
        aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(ownerUserId, 7L, "request-d", EXPIRY_TIMEOUT));

        assertThat(aiChatTurnRequestRepository.findByUserIdAndRequestId(otherUserId, "request-d")).isEmpty();
    }

    @Test
    void 종료_트랜잭션이_잠글_행을_id_로_읽는다() {
        long userId = nextUserId();
        Long turnRequestId = aiChatTurnRequestRepository.saveAndFlush(
                AiChatTurnRequest.createAccepted(userId, 7L, "request-e", EXPIRY_TIMEOUT)).getId();

        // 잠금 조회는 종료 트랜잭션(성공 확정·실패 기록·만료 복구)의 첫 단계다 — 잠근 뒤 상태를 보고
        // 종료 여부를 판정하므로, 잠금 없이 읽은 값으로 판정하는 경로를 두지 않는다.
        AiChatTurnRequest locked = aiChatTurnRequestRepository.findByIdForUpdate(turnRequestId).orElseThrow();

        assertThat(locked.getId()).isEqualTo(turnRequestId);
        assertThat(locked.getStatus()).isEqualTo(AiChatTurnRequest.Status.ACCEPTED);
        assertThat(locked.isTerminal()).isFalse();
    }

    @Test
    void 없는_요청_id_로_잠금_조회하면_비어_있다() {
        assertThat(aiChatTurnRequestRepository.findByIdForUpdate(9_999_999L)).isEmpty();
    }
}
