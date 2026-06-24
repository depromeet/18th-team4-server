package com.readum.model.summary.repository.projection;

import java.time.LocalDateTime;

/**
 * 사용자 본인의 감상 기록(완성된 감상문) 목록 조회용 projection.
 * Summary / AiChatSession / UserBook / Book 사이에 JPA 연관관계가 없어 @Query 의 on 절로 직접 join 한 결과를 담는다.
 * sessionTitle 은 비동기 제목 생성이 실패한 드문 경우에만 null 일 수 있다.
 */
public record SummaryHistoryProjection(
        Long summaryId,
        String bookTitle,
        String sessionTitle,
        LocalDateTime createdAt
) {
}
