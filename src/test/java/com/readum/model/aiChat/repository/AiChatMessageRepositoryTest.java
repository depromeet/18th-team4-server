package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AiChatMessageRepositoryTest {

    @Autowired
    private AiChatMessageRepository aiChatMessageRepository;

    private static long sessionIdSeq = 9_000_000L;

    private static synchronized Long nextSessionId() {
        sessionIdSeq += 1;
        return sessionIdSeq;
    }

    @Test
    @DisplayName("페이지 조회는 createdAt DESC, id DESC 정렬을 유지한다")
    void 페이지_조회_정렬() {
        Long sessionId = nextSessionId();
        for (int i = 1; i <= 5; i++) {
            aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "메시지 " + i));
        }

        Page<AiChatMessage> page = aiChatMessageRepository
                .findBySessionIdOrderByCreatedAtDescIdDesc(sessionId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getContent().get(0).getContent()).isEqualTo("메시지 5");
        assertThat(page.getContent().get(4).getContent()).isEqualTo("메시지 1");
    }

    @Test
    @DisplayName("findRecentForContextWindow 는 status=COMPLETED 인 USER/ASSISTANT 만 최신순으로 N개")
    void 컨텍스트_윈도우_쿼리() {
        Long sessionId = nextSessionId();
        // 5개 USER + 5개 ASSISTANT 정상 + 1개 ASSISTANT FAILED 인터리빙
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u1"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "a1", 1, 1, 2));
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u2"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantFailed(sessionId, "partial", 1, 0, 1));
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u3"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "a3", 1, 1, 2));

        List<AiChatMessage> recent = aiChatMessageRepository
                .findRecentForContextWindow(sessionId, PageRequest.of(0, 40));

        // FAILED 1개 제외 → 5개
        assertThat(recent).hasSize(5);
        // 최신순 (DESC) 이므로 첫 요소가 a3
        assertThat(recent.get(0).getContent()).isEqualTo("a3");
        assertThat(recent).extracting(AiChatMessage::getStatus)
                .allMatch(status -> status == AiChatMessage.Status.COMPLETED);
    }

    @Test
    @DisplayName("findRecentForContextWindow 는 Pageable 의 size 만큼만 반환")
    void 컨텍스트_윈도우_사이즈_제한() {
        Long sessionId = nextSessionId();
        for (int i = 1; i <= 50; i++) {
            aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "메시지 " + i));
        }

        List<AiChatMessage> recent = aiChatMessageRepository
                .findRecentForContextWindow(sessionId, PageRequest.of(0, 40));

        assertThat(recent).hasSize(40);
        assertThat(recent.get(0).getContent()).isEqualTo("메시지 50");
    }
}
