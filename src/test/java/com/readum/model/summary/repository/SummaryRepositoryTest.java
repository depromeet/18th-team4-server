package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.user.entity.UserBook;
import com.readum.model.user.repository.UserBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SummaryRepositoryTest {

    @Autowired
    private SummaryRepository summaryRepository;

    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;

    @Autowired
    private UserBookRepository userBookRepository;

    private static long userIdSeq = 9_200_000L;
    private static long bookIdSeq = 9_200_000L;

    private static synchronized Long nextUserId() {
        userIdSeq += 1;
        return userIdSeq;
    }

    private static synchronized Long nextBookId() {
        bookIdSeq += 1;
        return bookIdSeq;
    }

    @Test
    @DisplayName("findTopByAiChatSessionIdOrderByIdDesc: 같은 세션의 여러 감상문 중 가장 최근 행을 반환한다")
    void 최신_감상문_조회() {
        UserBook userBook = userBookRepository.save(UserBook.create(nextUserId(), nextBookId()));
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));

        summaryRepository.save(Summary.createFailed(userBook.getId(), session.getId()));
        Summary latest = summaryRepository.save(
                Summary.createCompleted(userBook.getId(), session.getId(), "제목", "본문", "인용"));

        Optional<Summary> found = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(session.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(latest.getId());
        assertThat(found.get().getStatus()).isEqualTo(Summary.Status.COMPLETED);
    }

    @Test
    @DisplayName("findTopByAiChatSessionIdOrderByIdDesc: 감상문이 없으면 빈 Optional 을 반환한다")
    void 감상문_없으면_빈_Optional() {
        UserBook userBook = userBookRepository.save(UserBook.create(nextUserId(), nextBookId()));
        AiChatSession session = aiChatSessionRepository.save(AiChatSession.create(userBook.getId()));

        Optional<Summary> found = summaryRepository.findTopByAiChatSessionIdOrderByIdDesc(session.getId());

        assertThat(found).isEmpty();
    }
}
