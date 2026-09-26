package com.readum.domain.summary.service;

import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감상문 작업 insert 를 독립 트랜잭션(REQUIRES_NEW)으로 격리한다.
 * 동시 적재로 unique 위반이 나면 이 내부 트랜잭션만 롤백되고, 호출자 트랜잭션은 오염되지 않는다.
 * (UserBookConflictReader 와 동일한 격리 패턴)
 */
@Component
@RequiredArgsConstructor
class SummaryJobInserter {

    private final SummaryJobRepository summaryJobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertPending(Long sessionId) {
        summaryJobRepository.save(SummaryJob.createPending(sessionId));
    }
}
