package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.BookChatSessionsResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.SessionLastChattedProjection;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 한 권(userBook)에 대한 모든 채팅 세션을, 책 정보 + 세션별 최신 감상문 본문·마지막 대화일과 함께 조회한다.
 * Repository 는 각자 자기 엔티티만 반환하고, 합성은 이 service 가 책임진다.
 */
@Service
@RequiredArgsConstructor
public class BookChatSessionSearchService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;

    public BookChatSessionsResult findByUserBook(Long userBookId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));
        UserBook userBook = userBookRepository.findByIdAndUserId(userBookId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));
        Book book = bookRepository.findById(userBook.getBookId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.USER_BOOK_NOT_FOUND));

        List<AiChatSession> sessions = aiChatSessionRepository.findByUserBookId(userBookId);
        List<Long> sessionIds = sessions.stream().map(AiChatSession::getId).toList();

        Map<Long, String> latestSummaryBySession = latestSummaryBodies(sessionIds);
        Map<Long, LocalDate> lastChattedBySession = lastChattedDates(sessionIds);

        List<BookChatSessionsResult.SessionItem> items = sessions.stream()
                .map(session -> new BookChatSessionsResult.SessionItem(
                        session.getId(),
                        latestSummaryBySession.get(session.getId()),
                        lastChattedBySession.getOrDefault(session.getId(), session.getCreatedAt().toLocalDate())))
                .sorted(Comparator.comparing(BookChatSessionsResult.SessionItem::lastChattedDate).reversed())
                .toList();

        return new BookChatSessionsResult(BookChatSessionsResult.BookInfo.from(book), items);
    }

    private Map<Long, String> latestSummaryBodies(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return summaryRepository.findLatestByAiChatSessionIdIn(sessionIds).stream()
                .collect(Collectors.toMap(Summary::getAiChatSessionId, Summary::getBody));
    }

    private Map<Long, LocalDate> lastChattedDates(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return aiChatMessageRepository
                .findLastChattedAtBySessionIds(sessionIds, AiChatMessage.Status.COMPLETED).stream()
                .collect(Collectors.toMap(
                        SessionLastChattedProjection::sessionId,
                        row -> row.lastChattedAt().toLocalDate()));
    }
}
