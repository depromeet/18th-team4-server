package com.readum.domain.user.userbook.service;

import com.readum.domain.user.userbook.dto.UserBookDeleteCommand;
import com.readum.domain.user.userbook.dto.UserBookDeleteResult;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록 도서(UserBook) 삭제의 cascade 동작을 실제 DB(H2 MySQL 모드) 에 적용해 검증한다.
 * <p>
 * 단위 테스트(UserBookDeleteServiceTest)가 "어떤 순서로 무엇을 호출하는가" 를 본다면,
 * 이 통합 테스트는 "실제로 어떤 행이 사라지고 어떤 행이 남는가" 를 본다 —
 * FK/JPA cascade 없이 서비스가 명시 삭제하므로, 연관 데이터 제거와 격리(다른 도서·다른 사용자·공유 Book 마스터)를
 * 끝까지 확인해야 회귀를 잡을 수 있다.
 */
@SpringBootTest
@Transactional
class UserBookDeleteServiceCascadeTest {

    @Autowired
    private UserBookDeleteService userBookDeleteService;

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

    @Test
    void 도서를_삭제하면_그_도서의_세션_메시지_감상은_모두_사라지고_다른_도서_데이터와_Book_마스터는_보존된다() {
        User user = persistUser();
        Book bookMaster1 = persistBook("ext-cascade-1");
        Book bookMaster2 = persistBook("ext-cascade-2");

        UserBook userBook1 = persistUserBook(user.getId(), bookMaster1.getId());
        UserBook userBook2 = persistUserBook(user.getId(), bookMaster2.getId());

        // 삭제 대상 도서를 "마지막 선택 도서" 로 지정 → 삭제 후 null 로 정리되는지 확인
        user.selectBook(userBook1.getId());
        userRepository.saveAndFlush(user);

        // userBook1: 세션 2개 + 각 세션의 메시지 + 감상 1개
        AiChatSession session1a = persistSession(userBook1.getId());
        AiChatSession session1b = persistSession(userBook1.getId());
        AiChatMessage message1a = persistUserMessage(session1a.getId(), "책1 첫 질문");
        AiChatMessage message1b = persistUserMessage(session1b.getId(), "책1 다른 세션 질문");
        Summary summary1 = persistSummary(userBook1.getId(), session1a.getId());

        // userBook2: 세션 1개 + 메시지 + 감상 1개 (모두 보존되어야 함)
        AiChatSession session2 = persistSession(userBook2.getId());
        AiChatMessage message2 = persistUserMessage(session2.getId(), "책2 질문");
        Summary summary2 = persistSummary(userBook2.getId(), session2.getId());

        long bookMasterCountBefore = bookRepository.count();

        UserBookDeleteResult result = userBookDeleteService.execute(
                new UserBookDeleteCommand(user.getSessionId(), userBook1.getId()));

        // 삭제 카운트가 실제 삭제된 행 수와 일치
        assertThat(result.userBookId()).isEqualTo(userBook1.getId());
        assertThat(result.deletedSessions()).isEqualTo(2);
        assertThat(result.deletedMessages()).isEqualTo(2);
        assertThat(result.deletedSummaries()).isEqualTo(1);

        // 삭제 대상 도서와 그 도서에 매달린 데이터는 전부 사라짐
        assertThat(userBookRepository.findById(userBook1.getId())).isEmpty();
        assertThat(aiChatSessionRepository.findById(session1a.getId())).isEmpty();
        assertThat(aiChatSessionRepository.findById(session1b.getId())).isEmpty();
        assertThat(aiChatMessageRepository.findById(message1a.getId())).isEmpty();
        assertThat(aiChatMessageRepository.findById(message1b.getId())).isEmpty();
        assertThat(summaryRepository.findById(summary1.getId())).isEmpty();

        // 다른 도서(userBook2) 의 데이터는 전부 보존
        assertThat(userBookRepository.findById(userBook2.getId())).isPresent();
        assertThat(aiChatSessionRepository.findById(session2.getId())).isPresent();
        assertThat(aiChatMessageRepository.findById(message2.getId())).isPresent();
        assertThat(summaryRepository.findById(summary2.getId())).isPresent();

        // 여러 사용자가 공유하는 Book 마스터는 한 행도 삭제되지 않음
        assertThat(bookRepository.count()).isEqualTo(bookMasterCountBefore);
        assertThat(bookRepository.findById(bookMaster1.getId())).isPresent();
        assertThat(bookRepository.findById(bookMaster2.getId())).isPresent();

        // 삭제된 도서를 가리키던 마지막 선택 참조는 null 로 정리됨 (dangling 참조 방지)
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLastSelectedUserBookId()).isNull();
    }

    @Test
    void 같은_Book_을_공유하는_다른_사용자의_도서_데이터와_공유_Book_마스터는_삭제에_영향받지_않는다() {
        Book sharedBook = persistBook("ext-shared");
        User userA = persistUser();
        User userB = persistUser();

        // 두 사용자가 같은 Book 마스터를 각자의 책장에 등록 (uk 는 user_id+book_id 라 사용자별로 허용)
        UserBook userBookA = persistUserBook(userA.getId(), sharedBook.getId());
        UserBook userBookB = persistUserBook(userB.getId(), sharedBook.getId());

        userA.selectBook(userBookA.getId());
        userB.selectBook(userBookB.getId());
        userRepository.saveAndFlush(userA);
        userRepository.saveAndFlush(userB);

        AiChatSession sessionA = persistSession(userBookA.getId());
        AiChatMessage messageA = persistUserMessage(sessionA.getId(), "A 의 질문");
        Summary summaryA = persistSummary(userBookA.getId(), sessionA.getId());

        AiChatSession sessionB = persistSession(userBookB.getId());
        AiChatMessage messageB = persistUserMessage(sessionB.getId(), "B 의 질문");
        Summary summaryB = persistSummary(userBookB.getId(), sessionB.getId());

        userBookDeleteService.execute(
                new UserBookDeleteCommand(userA.getSessionId(), userBookA.getId()));

        // A 의 도서와 연관 데이터는 사라짐
        assertThat(userBookRepository.findById(userBookA.getId())).isEmpty();
        assertThat(aiChatSessionRepository.findById(sessionA.getId())).isEmpty();
        assertThat(aiChatMessageRepository.findById(messageA.getId())).isEmpty();
        assertThat(summaryRepository.findById(summaryA.getId())).isEmpty();

        // B 의 도서와 연관 데이터는 전부 보존 (같은 Book 을 공유해도 격리됨)
        assertThat(userBookRepository.findById(userBookB.getId())).isPresent();
        assertThat(aiChatSessionRepository.findById(sessionB.getId())).isPresent();
        assertThat(aiChatMessageRepository.findById(messageB.getId())).isPresent();
        assertThat(summaryRepository.findById(summaryB.getId())).isPresent();

        // 공유 Book 마스터 보존
        assertThat(bookRepository.findById(sharedBook.getId())).isPresent();

        // A 의 마지막 선택만 null 로 정리되고, B 의 마지막 선택은 그대로
        User reloadedA = userRepository.findById(userA.getId()).orElseThrow();
        User reloadedB = userRepository.findById(userB.getId()).orElseThrow();
        assertThat(reloadedA.getLastSelectedUserBookId()).isNull();
        assertThat(reloadedB.getLastSelectedUserBookId()).isEqualTo(userBookB.getId());
    }

    private User persistUser() {
        return userRepository.saveAndFlush(User.create(UUID.randomUUID()));
    }

    private Book persistBook(String externalId) {
        return bookRepository.saveAndFlush(
                Book.create(externalId, "제목-" + externalId, "저자", "출판사", 2024, "http://cover/" + externalId));
    }

    private UserBook persistUserBook(Long userId, Long bookId) {
        return userBookRepository.saveAndFlush(UserBook.create(userId, bookId));
    }

    private AiChatSession persistSession(Long userBookId) {
        return aiChatSessionRepository.saveAndFlush(AiChatSession.create(userBookId));
    }

    private AiChatMessage persistUserMessage(Long sessionId, String content) {
        return aiChatMessageRepository.saveAndFlush(AiChatMessage.createUserMessage(sessionId, content));
    }

    private Summary persistSummary(Long userBookId, Long sessionId) {
        return summaryRepository.saveAndFlush(Summary.createInProgress(userBookId, sessionId));
    }
}
