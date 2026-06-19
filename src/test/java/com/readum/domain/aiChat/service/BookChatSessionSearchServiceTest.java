package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.BookChatSessionsResult;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.Book;
import com.readum.model.book.repository.BookRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BookChatSessionSearchServiceTest {

    @Autowired
    private BookChatSessionSearchService bookChatSessionSearchService;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserBookRepository userBookRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;
    @Autowired
    private AiChatMessageRepository aiChatMessageRepository;
    @Autowired
    private SummaryRepository summaryRepository;

    private static long externalSeq = 9_300_000L;

    private static synchronized String nextExternalId() {
        externalSeq += 1;
        return "ext-" + externalSeq;
    }

    @Test
    @DisplayName("책 정보 + 세션별 최신 감상문 본문 + 마지막 대화일을 마지막 대화일 최신순으로 반환한다")
    void 책별_세션_목록_조회() {
        User user = userRepository.save(User.create(UUID.randomUUID(), "닉네임"));
        Book book = bookRepository.save(Book.create(
                nextExternalId(), "데미안", "헤세", "민음사", 2020, "http://img/x.jpg"));
        UserBook userBook = userBookRepository.save(UserBook.create(user.getId(), book.getId()));

        LocalDateTime now = LocalDateTime.now();

        // 오래 전 대화한 세션 — 감상문 1건(1:1 모델)
        AiChatSession older = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));
        aiChatMessageRepository.save(AiChatMessageFixture.userMessageAt(older.getId(), "옛 대화", now.minusDays(2)));
        summaryRepository.save(Summary.createCompleted(userBook.getId(), older.getId(), "새 제목", "최신 본문"));

        // 최근 대화한 세션 — 감상문 없음(null)
        AiChatSession newer = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));
        aiChatMessageRepository.save(AiChatMessageFixture.userMessageAt(newer.getId(), "최근 대화", now.minusHours(1)));

        BookChatSessionsResult result = bookChatSessionSearchService.findByUserBook(
                userBook.getId(), user.getSessionId());

        assertThat(result.book().title()).isEqualTo("데미안");
        assertThat(result.book().publishedYear()).isEqualTo(2020);
        assertThat(result.book().publisher()).isEqualTo("민음사");
        assertThat(result.book().coverImageUrl()).isEqualTo("http://img/x.jpg");

        // 마지막 대화일 최신순 — newer 가 먼저
        assertThat(result.sessions()).extracting(BookChatSessionsResult.SessionItem::sessionId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(result.sessions().get(0).summaryTitle()).isNull();
        assertThat(result.sessions().get(0).latestSummaryContent()).isNull();
        assertThat(result.sessions().get(0).lastChattedDate()).isEqualTo(now.minusHours(1).toLocalDate());
        assertThat(result.sessions().get(1).summaryTitle()).isEqualTo("새 제목");
        assertThat(result.sessions().get(1).latestSummaryContent()).isEqualTo("최신 본문");
        assertThat(result.sessions().get(1).lastChattedDate()).isEqualTo(now.minusDays(2).toLocalDate());
    }

    @Test
    @DisplayName("메시지가 없는 세션의 마지막 대화일은 세션 생성일로 fallback 된다")
    void 메시지_없으면_생성일_fallback() {
        User user = userRepository.save(User.create(UUID.randomUUID(), "닉네임"));
        Book book = bookRepository.save(Book.create(nextExternalId(), "책", null, null, null, null));
        UserBook userBook = userBookRepository.save(UserBook.create(user.getId(), book.getId()));
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));

        BookChatSessionsResult result = bookChatSessionSearchService.findByUserBook(
                userBook.getId(), user.getSessionId());

        assertThat(result.sessions()).hasSize(1);
        assertThat(result.sessions().get(0).lastChattedDate())
                .isEqualTo(session.getCreatedAt().toLocalDate());
    }

    @Test
    @DisplayName("다른 사용자의 userBook 을 조회하면 NotFoundException")
    void 타인_userBook_조회_차단() {
        User owner = userRepository.save(User.create(UUID.randomUUID(), "주인"));
        User other = userRepository.save(User.create(UUID.randomUUID(), "타인"));
        Book book = bookRepository.save(Book.create(nextExternalId(), "책", null, null, null, null));
        UserBook userBook = userBookRepository.save(UserBook.create(owner.getId(), book.getId()));

        assertThatThrownBy(() -> bookChatSessionSearchService.findByUserBook(
                userBook.getId(), other.getSessionId()))
                .isInstanceOf(NotFoundException.class);
    }
}
