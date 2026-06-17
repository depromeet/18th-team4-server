package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    public void execute(Long sessionId, SummaryJob.ExecutionMode executionMode) {
        if (summaryJobRepository.existsByActiveSessionId(sessionId)) {
            return;
        }
        try {
            summaryJobInserter.insertPending(sessionId, executionMode);
        } catch (DataIntegrityViolationException e) {
            log.debug("감상문 작업 적재 경합 — 이미 활성 작업 존재 sessionId={}", sessionId);
        }
    }
}
