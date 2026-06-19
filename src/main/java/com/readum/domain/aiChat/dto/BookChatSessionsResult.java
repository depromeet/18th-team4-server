package com.readum.domain.aiChat.dto;

import com.readum.model.book.entity.Book;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 권(책)에 대한 모든 채팅 세션 목록 조회 결과.
 * 책 정보(머리말)와, 세션별 최신 감상문 본문·마지막 대화일을 담는다.
 */
public record BookChatSessionsResult(
        BookInfo book,
        List<SessionItem> sessions
) {
    public record BookInfo(String title, Integer publishedYear, String publisher, String coverImageUrl) {
        public static BookInfo from(Book book) {
            return new BookInfo(book.getTitle(), book.getPublishedYear(), book.getPublisher(), book.getCoverUrl());
        }
    }

    public record SessionItem(Long sessionId, String sessionTitle, String latestSummaryContent,
                              LocalDate lastChattedDate) {}
}
