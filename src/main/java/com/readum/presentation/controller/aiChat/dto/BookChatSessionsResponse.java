package com.readum.presentation.controller.aiChat.dto;

import com.readum.domain.aiChat.dto.BookChatSessionsResult;

import java.time.LocalDate;
import java.util.List;

public record BookChatSessionsResponse(
        BookResponse book,
        List<SessionResponse> sessions
) {
    public record BookResponse(String title, Integer publishedYear, String publisher, String coverImageUrl) {}

    public record SessionResponse(Long sessionId, String summaryTitle, String latestSummaryContent,
                                  LocalDate lastChattedDate) {}

    public static BookChatSessionsResponse from(BookChatSessionsResult result) {
        BookChatSessionsResult.BookInfo book = result.book();
        return new BookChatSessionsResponse(
                new BookResponse(book.title(), book.publishedYear(), book.publisher(), book.coverImageUrl()),
                result.sessions().stream()
                        .map(session -> new SessionResponse(
                                session.sessionId(),
                                session.summaryTitle(),
                                session.latestSummaryContent(),
                                session.lastChattedDate()))
                        .toList());
    }
}
