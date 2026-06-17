package com.readum.domain.summary.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlyReadingRecordResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.aiChat.repository.projection.SessionLastChattedProjection;
import com.readum.model.summary.repository.SummaryJobRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 모든 조회 메서드가 여러 Repository 를 한 트랜잭션으로 묶어 일관된 스냅샷으로 읽는다
 * (Transaction Convention 의 {@code @Transactional(readOnly = true)} 예외 조항).
 */
@Service
@RequiredArgsConstructor
public class SummarySearchService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryJobRepository summaryJobRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;

    @Transactional(readOnly = true)
    public List<MonthlyReadingRecordResult> findMonthly(YearMonth yearMonth, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        List<UserBook> userBooks = userBookRepository.findByUserId(user.getId());
        if (userBooks.isEmpty()) {
            return List.of();
        }
        List<Long> userBookIds = userBooks.stream().map(UserBook::getId).toList();

        List<AiChatSession> sessions = aiChatSessionRepository.findByUserBookIdIn(userBookIds);
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> sessionIds = sessions.stream().map(AiChatSession::getId).toList();

        Map<Long, LocalDateTime> lastChattedBySession = aiChatMessageRepository
                .findLastChattedAtBySessionIds(sessionIds, AiChatMessage.Status.COMPLETED).stream()
                .collect(Collectors.toMap(
                        SessionLastChattedProjection::sessionId,
                        SessionLastChattedProjection::lastChattedAt));

        LocalDateTime startInclusive = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime endExclusive = yearMonth.plusMonths(1).atDay(1).atStartOfDay();

        // COMPLETED 메시지가 없는 세션(맵에 없음)은 마지막 채팅일이 없어 제외된다.
        List<AiChatSession> sessionsInMonth = sessions.stream()
                .filter(session -> {
                    LocalDateTime lastChattedAt = lastChattedBySession.get(session.getId());
                    return lastChattedAt != null
                            && !lastChattedAt.isBefore(startInclusive)
                            && lastChattedAt.isBefore(endExclusive);
                })
                .toList();
        if (sessionsInMonth.isEmpty()) {
            return List.of();
        }

        List<Long> survivingSessionIds = sessionsInMonth.stream().map(AiChatSession::getId).toList();
        Map<Long, Long> summaryIdBySession = summaryRepository.findLatestByAiChatSessionIdIn(survivingSessionIds)
                .stream()
                .collect(Collectors.toMap(Summary::getAiChatSessionId, Summary::getId));

        Map<Long, Long> bookIdByUserBookId = userBooks.stream()
                .collect(Collectors.toMap(UserBook::getId, UserBook::getBookId));
        List<Long> bookIds = sessionsInMonth.stream()
                .map(session -> bookIdByUserBookId.get(session.getUserBookId()))
                .distinct()
                .toList();
        Map<Long, Book> bookById = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));

        return sessionsInMonth.stream()
                .map(session -> MonthlyReadingRecordResult.from(
                        session,
                        bookById.get(bookIdByUserBookId.get(session.getUserBookId())).getTitle(),
                        summaryIdBySession.get(session.getId()),
                        lastChattedBySession.get(session.getId())))
                .sorted(Comparator.comparing(MonthlyReadingRecordResult::lastChattedAt)
                        .thenComparing(MonthlyReadingRecordResult::chatSessionId)
                        .reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public SummaryResult findById(Long summaryId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        Summary summary = summaryRepository.findById(summaryId)
                .orElseThrow(() -> new NotFoundException(SummaryErrorCode.SUMMARY_NOT_FOUND));

        userBookRepository.findByIdAndUserId(summary.getUserBookId(), user.getId())
                .orElseThrow(() -> new NotFoundException(SummaryErrorCode.SUMMARY_NOT_FOUND));

        return SummaryResult.from(summary);
    }

    @Transactional(readOnly = true)
    public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        AiChatSession session = aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        // "생성 중" 은 차단 판정 조건(PENDING 또는 유효 점유 PROCESSING)으로 판정(폴링 계약: 409 유지)
        if (summaryJobRepository.existsBlockingSummaryJob(sessionId, LocalDateTime.now())) {
            throw new ConflictException(SummaryErrorCode.SUMMARY_IN_PROGRESS);
        }

        Summary summary = summaryRepository.findByAiChatSessionId(sessionId)
                .orElseThrow(() -> new NotFoundException(SummaryErrorCode.SUMMARY_NOT_YET_CREATED));

        return SummaryResult.from(summary);
    }
}
