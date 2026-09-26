package com.readum.domain.summary.service;

import com.readum.domain.aiChat.service.policy.SummaryDraftPolicy;
import com.readum.model.summary.repository.SummaryJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 자동 적재의 DB 쓰기 한 단계 — 집합 단위 단일 INSERT 를 선언적 트랜잭션 안에서 수행한다.
 *
 * <p>적재를 부르는 쪽(스케줄러)이 그 전에 공급자 상태를 Redis 로 확인하는데, 그 왕복이 트랜잭션 안에 들어가면
 * 그 시간만큼 커넥션을 붙잡는다. 그래서 트랜잭션 경계를 쓰기에만 두고, 바깥 오케스트레이터는 비-트랜잭션으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class SummaryJobBulkEnqueuer {

    private final SummaryJobRepository summaryJobRepository;

    /**
     * 조회 범위의 대상을 <b>한 번에 전부</b> 적재한다. 건수 상한을 두지 않는다 — 차단 중에는 이 적재 자체가
     * 일어나지 않으므로(부르는 쪽이 먼저 가용을 확인한다) "조금만 넣어 보며 살아났는지 떠본다" 는 회차가 없다.
     *
     * @return 실제 적재된 행 수
     */
    @Transactional
    public int enqueueEligibleSessions(LocalDateTime since, LocalDateTime now) {
        return summaryJobRepository.enqueuePendingForEligibleSessions(
                SummaryDraftPolicy.MIN_ACCUMULATED_TOKENS, since, now);
    }
}
