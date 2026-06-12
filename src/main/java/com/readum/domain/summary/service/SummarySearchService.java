package com.readum.domain.summary.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlySummaryResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
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

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SummarySearchService {

    private final UserRepository userRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryRepository summaryRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;

    /**
     * 여러 Repository 를 한 트랜잭션으로 묶어 일관된 상태로 읽는다 (Transaction Convention 예외 조항).
     */
    @Transactional(readOnly = true)
    public List<MonthlySummaryResult> findMonthly(YearMonth yearMonth, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        List<UserBook> userBooks = userBookRepository.findByUserId(user.getId());
        if (userBooks.isEmpty()) {
            return List.of();
        }

        List<Long> userBookIds = userBooks.stream().map(UserBook::getId).toList();
        List<Summary> summaries = summaryRepository.findMonthlyCompleted(
                userBookIds, yearMonth.atDay(1), yearMonth.atEndOfMonth());

        Map<Long, Long> bookIdByUserBookId = userBooks.stream()
                .collect(Collectors.toMap(UserBook::getId, UserBook::getBookId));
        List<Long> bookIds = userBooks.stream().map(UserBook::getBookId).distinct().toList();
        Map<Long, Book> bookById = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));

        return summaries.stream()
                .map(summary -> MonthlySummaryResult.from(
                        summary, bookById.get(bookIdByUserBookId.get(summary.getUserBookId())).getTitle()))
                .toList();
    }

    public SummaryResult findBySessionId(Long sessionId, String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        aiChatSessionRepository.findByIdAndOwner(sessionId, user.getId())
                .orElseThrow(() -> new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND));

        Summary summary = summaryRepository.findByAiChatSessionId(sessionId)
                .orElseThrow(() -> new NotFoundException(SummaryErrorCode.SUMMARY_NOT_YET_CREATED));

        return switch (summary.getStatus()) {
            case COMPLETED -> SummaryResult.from(summary);
            case IN_PROGRESS -> throw new ConflictException(SummaryErrorCode.SUMMARY_IN_PROGRESS);
            case FAILED -> throw new ConflictException(SummaryErrorCode.SUMMARY_GENERATION_FAILED);
        };
    }
}
