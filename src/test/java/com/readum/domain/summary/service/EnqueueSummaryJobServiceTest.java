package com.readum.domain.summary.service;

import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EnqueueSummaryJobServiceTest {

    @Mock
    private SummaryJobRepository summaryJobRepository;

    @Mock
    private SummaryJobInserter summaryJobInserter;

    @InjectMocks
    private EnqueueSummaryJobService enqueueSummaryJobService;

    @Test
    void 활성작업이_없으면_PENDING_작업을_저장한다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(false);

        enqueueSummaryJobService.execute(1L);

        verify(summaryJobInserter).insertPending(1L);
    }

    @Test
    void 활성작업이_이미_있으면_저장하지_않는다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(true);

        enqueueSummaryJobService.execute(1L);

        verify(summaryJobInserter, never()).insertPending(anyLong());
    }

    @Test
    void 동시적재로_unique위반이_나도_예외를_삼킨다() {
        given(summaryJobRepository.existsByActiveSessionId(1L)).willReturn(false);
        doThrow(new DataIntegrityViolationException("dup")).when(summaryJobInserter).insertPending(1L);

        assertThatCode(() -> enqueueSummaryJobService.execute(1L)).doesNotThrowAnyException();
    }
}
