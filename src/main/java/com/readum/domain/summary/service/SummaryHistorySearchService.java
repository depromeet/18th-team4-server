package com.readum.domain.summary.service;

import com.readum.domain.summary.dto.SummaryHistoryItemResult;
import com.readum.domain.summary.dto.SummaryHistoryListCommand;
import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

/**
 * 사용자 본인의 감상 기록 목록을 최신순으로 Slice 조회한다. 세션(=책)당 가장 최근 감상문 1건만 노출한다.
 * 페이지 크기는 20개로 고정한다.
 *
 * 형제 조회 서비스(AiChatSessionSearchService 등)와 동일하게 @Transactional(readOnly) 를 붙이지 않는다.
 * 단일 Repository 호출이므로 서비스 레벨의 트랜잭션이 불필요하다.
 */
@Service
@RequiredArgsConstructor
public class SummaryHistorySearchService {

    private static final int PAGE_SIZE = 20;

    private final SummaryRepository summaryRepository;

    public SummaryHistoryListResult findMyHistory(SummaryHistoryListCommand command) {
        int pageIndex = Math.max(0, command.page() - 1);
        Slice<SummaryHistoryProjection> slice = summaryRepository.findLatestHistoryByUserId(
                command.userId(), PageRequest.of(pageIndex, PAGE_SIZE));

        return new SummaryHistoryListResult(
                slice.getContent().stream().map(SummaryHistoryItemResult::from).toList(),
                command.page(),
                PAGE_SIZE,
                slice.hasNext()
        );
    }
}
