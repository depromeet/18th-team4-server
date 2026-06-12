package com.readum.model.summary.repository;

import com.readum.model.summary.entity.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryRepositoryTest {

    @Autowired
    private SummaryRepository summaryRepository;

    private static long sessionIdSeq = 900_000L;

    private static long nextSessionId() {
        return sessionIdSeq++;
    }

    private Summary persistCompleted(Long userBookId, LocalDate summaryDate) {
        Summary summary = summaryRepository.save(
                Summary.createInProgress(userBookId, nextSessionId(), summaryDate));
        summary.complete("제목", "본문", null);
        return summaryRepository.save(summary);
    }

    @Test
    @DisplayName("월 범위(월초·월말 포함)의 감상문만 반환한다")
    void findMonthlyCompleted_월_경계_포함() {
        Long userBookId = 1L;
        Summary firstDay = persistCompleted(userBookId, LocalDate.of(2026, 6, 1));
        Summary lastDay = persistCompleted(userBookId, LocalDate.of(2026, 6, 30));
        persistCompleted(userBookId, LocalDate.of(2026, 5, 31));
        persistCompleted(userBookId, LocalDate.of(2026, 7, 1));

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId)
                .containsExactly(lastDay.getId(), firstDay.getId());   // 최신순: 6/30 이 먼저
    }

    @Test
    @DisplayName("COMPLETED 가 아닌 감상문은 반환하지 않는다")
    void findMonthlyCompleted_미완성_제외() {
        Long userBookId = 2L;
        LocalDate date = LocalDate.of(2026, 6, 10);
        Summary completed = persistCompleted(userBookId, date);
        summaryRepository.save(Summary.createInProgress(userBookId, nextSessionId(), date)); // IN_PROGRESS
        Summary failed = summaryRepository.save(Summary.createInProgress(userBookId, nextSessionId(), date));
        failed.fail();
        summaryRepository.save(failed);

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId).containsExactly(completed.getId());
    }

    @Test
    @DisplayName("조회 대상 userBookId 가 아닌 감상문은 반환하지 않는다")
    void findMonthlyCompleted_다른_userBook_제외() {
        Long mine = 3L;
        Long others = 4L;
        LocalDate date = LocalDate.of(2026, 6, 10);
        Summary myRecord = persistCompleted(mine, date);
        persistCompleted(others, date);

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(mine), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(found).extracting(Summary::getId).containsExactly(myRecord.getId());
    }

    @Test
    @DisplayName("최신순으로 정렬한다 — summaryDate 내림차순, 같은 날짜는 생성 시각·id 내림차순")
    void findMonthlyCompleted_최신순_정렬() {
        Long userBookId = 5L;
        Summary laterDate = persistCompleted(userBookId, LocalDate.of(2026, 6, 20));
        Summary sameDayEarlier = persistCompleted(userBookId, LocalDate.of(2026, 6, 5));
        Summary sameDayLater = persistCompleted(userBookId, LocalDate.of(2026, 6, 5));

        List<Summary> found = summaryRepository.findMonthlyCompleted(
                List.of(userBookId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 6/20 이 맨 앞, 같은 6/5 안에서는 나중에 만든 기록(생성 시각이 같으면 id 가 큰 쪽)이 먼저
        assertThat(found).extracting(Summary::getId)
                .containsExactly(laterDate.getId(), sameDayLater.getId(), sameDayEarlier.getId());
    }
}
