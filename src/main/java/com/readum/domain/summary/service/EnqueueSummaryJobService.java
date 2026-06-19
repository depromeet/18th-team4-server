package com.readum.domain.summary.service;

import com.readum.domain.summary.dto.EnqueueSummaryJobResult;
import com.readum.model.summary.repository.SummaryJobRepository;
import jakarta.persistence.LockTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 세션에 대한 감상문 생성 작업을 멱등하게 적재한다.
 * 같은 세션에 이미 활성 작업이 있으면 새로 만들지 않는다(active_session_id unique).
 * insert 는 REQUIRES_NEW 로 격리(SummaryJobInserter)하므로, 동시 적재로 unique 위반이 나도
 * 호출자 트랜잭션을 오염시키지 않고 조용히 무시할 수 있다.
 * (execute 자체에는 @Transactional 을 두지 않는다 — 위반 예외 catch 가 commit 단계에서
 *  UnexpectedRollbackException 으로 되살아나는 것을 피하기 위함.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnqueueSummaryJobService {

    private final SummaryJobRepository summaryJobRepository;
    private final SummaryJobInserter summaryJobInserter;

    /**
     * 워커 폴링과 적재 INSERT 가 순간 경합하면 INSERT 가 락 대기 끝에 실패(MySQL 1205 →
     * {@link CannotAcquireLockException})할 수 있다(#92). 이 순간 경합만 짧은 backoff 재시도로 구제한다.
     * 매 재시도는 멱등 precheck 를 다시 거치고 {@code insertPending} 의 새 REQUIRES_NEW 트랜잭션으로 들어간다.
     * unique 위반({@link DataIntegrityViolationException})은 catch 에서 멱등 처리되어 전파되지 않으므로
     * 재시도 대상에서 빠진다.
     *
     * <p>전제: 재시도가 의미 있으려면 INSERT 가 빨리 실패해야 한다 — RDS {@code innodb_lock_wait_timeout}
     * 을 짧게(보수적으로 약 10초) 둔 환경과 함께 적용한다. 이 재시도는 짧은 순간 겹치는 경합만 구제한다.
     * 어떤 트랜잭션이 몇 분씩 락을 쥐고 있으면 재시도해도 매번 실패하므로(빨리 실패할 뿐이다),
     * 락을 오래 점유하는 상황까지 해결해 주지는 않는다.
     */
    @Retryable(
            retryFor = { CannotAcquireLockException.class, LockTimeoutException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 1000))
    public EnqueueSummaryJobResult execute(Long sessionId) {
        if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
            return new EnqueueSummaryJobResult(false);
        }
        try {
            summaryJobInserter.insertPending(sessionId);
            return new EnqueueSummaryJobResult(true);
        } catch (DataIntegrityViolationException e) {
            // unique 경합으로 확인되면 멱등 skip, 그 외 무결성 위반(FK/NOT NULL 등)은 재던져 드러낸다.
            if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
                log.debug("감상문 작업 적재 경합 — 이미 활성 작업 존재 sessionId={}", sessionId);
                return new EnqueueSummaryJobResult(false);
            }
            throw e;
        }
    }
}
