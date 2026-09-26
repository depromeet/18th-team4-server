package com.readum.infrastructure.summary.scheduler;

import com.readum.domain.summary.config.SummaryJobProperties;
import com.readum.domain.summary.service.SummaryGenerationWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Executor;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SummaryJobDispatcherTest {

    @Mock private SummaryGenerationWorker worker;
    @Mock private SummaryJobProperties properties;

    @Test
    void dispatch_는_풀크기만큼_처리_작업을_제출한다() {
        Executor directExecutor = Runnable::run; // 같은 스레드에서 즉시 실행
        given(properties.poolSize()).willReturn(3);
        SummaryJobDispatcher dispatcher = new SummaryJobDispatcher(worker, directExecutor, properties);

        dispatcher.dispatch();

        verify(worker, times(3)).processUntilEmpty();
    }
}
