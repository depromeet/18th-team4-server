package com.readum.domain.summary.service;

import com.readum.model.summary.repository.SummaryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private EnqueueSummaryJobService service;

    @Test
    void 활성작업_없으면_적재한다() {
        given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(false);

        var result = service.execute(7L);

        verify(summaryJobInserter).insertPending(7L);
        assertThat(result.enqueued()).isTrue();
    }

    @Test
    void 활성작업_있으면_적재하지_않는다() {
        given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(true);

        var result = service.execute(7L);

        verify(summaryJobInserter, never()).insertPending(anyLong());
        assertThat(result.enqueued()).isFalse();
    }

    @Test
    void unique_경합이면_삼키고_적재안됨으로_반환한다() {
        // 사전 체크 통과(false) → insert 가 unique 위반 → 재확인 시 이미 존재(true) → 경합으로 판단.
        given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(false, true);
        doThrow(new DataIntegrityViolationException("dup"))
                .when(summaryJobInserter).insertPending(7L);

        var result = service.execute(7L);

        assertThat(result.enqueued()).isFalse();
    }

    @Test
    void unique_경합이_아닌_무결성_위반은_재던진다() {
        // 사전 체크·재확인 모두 false → 진짜 무결성 오류 → 삼키지 않고 전파.
        given(summaryJobRepository.existsByActiveSessionId(7L)).willReturn(false, false);
        doThrow(new DataIntegrityViolationException("fk"))
                .when(summaryJobInserter).insertPending(7L);

        assertThatThrownBy(() -> service.execute(7L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
