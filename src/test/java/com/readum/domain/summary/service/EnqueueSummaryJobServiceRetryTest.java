package com.readum.domain.summary.service;

import com.readum.domain.summary.dto.EnqueueSummaryJobResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * summary_job 적재 INSERT 의 락 획득 실패 재시도 계약 테스트 (이슈 #92).
 *
 * <p>워커 폴링과 적재 INSERT 가 순간 경합하면 INSERT 가 락 대기 끝에 실패(MySQL 1205 → Spring
 * {@link CannotAcquireLockException})할 수 있다. 이 순간 경합을 짧은 backoff 재시도로 구제한다.
 * unique 위반({@link DataIntegrityViolationException})은 같은 세션 동시 적재의 멱등 경로이므로
 * 재시도하지 않는다.
 *
 * <p>{@code @Retryable} 은 AOP 프록시로만 동작하므로 컨텍스트를 띄워 검증한다.
 * INSERT 본체({@link SummaryJobInserter})만 mock 으로 대체해 실패/성공을 주입한다.
 */
@SpringBootTest
class EnqueueSummaryJobServiceRetryTest {

    @Autowired
    private EnqueueSummaryJobService enqueueSummaryJobService;

    @MockitoBean
    private SummaryJobInserter summaryJobInserter;

    @Test
    void 락_획득_실패는_짧은_backoff_로_재시도해_성공한다() {
        long sessionId = System.nanoTime();
        doThrow(new CannotAcquireLockException("lock wait timeout"))
                .doThrow(new CannotAcquireLockException("lock wait timeout"))
                .doNothing()
                .when(summaryJobInserter).insertPending(sessionId);

        EnqueueSummaryJobResult result = enqueueSummaryJobService.execute(sessionId);

        assertThat(result.enqueued()).isTrue();
        verify(summaryJobInserter, times(3)).insertPending(sessionId);
    }

    @Test
    void 락_획득_실패가_최대_시도를_넘으면_예외를_전파한다() {
        long sessionId = System.nanoTime();
        doThrow(new CannotAcquireLockException("lock wait timeout"))
                .when(summaryJobInserter).insertPending(sessionId);

        assertThatThrownBy(() -> enqueueSummaryJobService.execute(sessionId))
                .isInstanceOf(CannotAcquireLockException.class);
        verify(summaryJobInserter, times(3)).insertPending(sessionId);
    }

    @Test
    void unique_위반은_재시도하지_않는다() {
        long sessionId = System.nanoTime();
        doThrow(new DataIntegrityViolationException("duplicate active_session_id"))
                .when(summaryJobInserter).insertPending(sessionId);

        assertThatThrownBy(() -> enqueueSummaryJobService.execute(sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(summaryJobInserter, times(1)).insertPending(sessionId);
    }
}
