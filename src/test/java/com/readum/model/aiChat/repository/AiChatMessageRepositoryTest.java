package com.readum.model.aiChat.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
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
    @DisplayName("findVisibleHistory 는 COMPLETED 메시지만 createdAt DESC, id DESC 로 반환한다 (FAILED 제외)")
    void findVisibleHistory_필터링과_정렬() {
        Long sessionId = nextSessionId();
        // FAILED(부분 응답) 와 정상 메시지를 인터리빙
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u1"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "a1", 1, 1, 2));
        aiChatMessageRepository.save(AiChatMessage.createAssistantFailed(sessionId, "partial", 1, 0, 1));
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u2"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "a2", 1, 1, 2));

        Slice<AiChatMessage> slice = aiChatMessageRepository
                .findVisibleHistory(sessionId, PageRequest.of(0, 10));

        // FAILED 1개 제외 → 4개
        assertThat(slice.getContent()).hasSize(4);
        assertThat(slice.hasNext()).isFalse();
        // 최신순 (DESC) 이므로 가장 마지막에 저장된 a2 가 첫 요소, u1 이 마지막
        assertThat(slice.getContent().get(0).getContent()).isEqualTo("a2");
        assertThat(slice.getContent().get(3).getContent()).isEqualTo("u1");
        assertThat(slice.getContent())
                .extracting(AiChatMessage::getStatus)
                .containsOnly(AiChatMessage.Status.COMPLETED);
    }

    @Test
    @DisplayName("findVisibleHistory 는 Slice 의 hasNext 를 페이지 size 와 비교해 반환한다")
    void findVisibleHistory_hasNext() {
        Long sessionId = nextSessionId();
        for (int i = 1; i <= 5; i++) {
            aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "u" + i));
        }

        Slice<AiChatMessage> firstPage = aiChatMessageRepository
                .findVisibleHistory(sessionId, PageRequest.of(0, 3));
        assertThat(firstPage.getContent()).hasSize(3);
        assertThat(firstPage.hasNext()).isTrue();

        Slice<AiChatMessage> secondPage = aiChatMessageRepository
                .findVisibleHistory(sessionId, PageRequest.of(1, 3));
        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findRecentForContextAssembly 는 status=COMPLETED 인 USER/ASSISTANT 만 최신순으로 N개")
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
                .findRecentForContextAssembly(sessionId, PageRequest.of(0, 40));

        // FAILED 1개 제외 → 5개
        assertThat(recent).hasSize(5);
        // 최신순 (DESC) 이므로 첫 요소가 a3
        assertThat(recent.get(0).getContent()).isEqualTo("a3");
        assertThat(recent).extracting(AiChatMessage::getStatus)
                .allMatch(status -> status == AiChatMessage.Status.COMPLETED);
    }

    @Test
    @DisplayName("findRecentForContextAssembly 는 Pageable 의 size 만큼만 반환")
    void 컨텍스트_윈도우_사이즈_제한() {
        Long sessionId = nextSessionId();
        for (int i = 1; i <= 50; i++) {
            aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "메시지 " + i));
        }

        List<AiChatMessage> recent = aiChatMessageRepository
                .findRecentForContextAssembly(sessionId, PageRequest.of(0, 40));

        assertThat(recent).hasSize(40);
        assertThat(recent.get(0).getContent()).isEqualTo("메시지 50");
    }

    @Test
    @DisplayName("AC-5: REJECTED USER 메시지는 findRecentForContextAssembly 에서 제외된다")
    void 컨텍스트_윈도우_REJECTED_제외() {
        Long sessionId = nextSessionId();
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "정상 질문"));
        aiChatMessageRepository.save(AiChatMessage.createUserMessageRejected(sessionId, "차단된 질문"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "정상 응답", 1, 1, 2));

        List<AiChatMessage> recent = aiChatMessageRepository
                .findRecentForContextAssembly(sessionId, PageRequest.of(0, 40));

        assertThat(recent).hasSize(2);
        assertThat(recent).extracting(AiChatMessage::getContent)
                .doesNotContain("차단된 질문");
        assertThat(recent).extracting(AiChatMessage::getStatus)
                .containsOnly(AiChatMessage.Status.COMPLETED);
    }

    @Test
    @DisplayName("AC-6: findValidMessages 는 REJECTED 와 FAILED 를 제외하고 COMPLETED 만 반환한다 (감상문·제목 누수 차단)")
    void 유효_메시지_조회_COMPLETED_만() {
        Long sessionId = nextSessionId();
        aiChatMessageRepository.save(AiChatMessage.createUserMessageRejected(sessionId, "차단된 질문"));
        aiChatMessageRepository.save(AiChatMessage.createUserMessage(sessionId, "정상 질문"));
        aiChatMessageRepository.save(AiChatMessage.createAssistantFailed(sessionId, "부분 응답", 1, 0, 1));
        aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(sessionId, "정상 응답", 1, 1, 2));

        List<AiChatMessage> valid = aiChatMessageRepository
                .findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId);

        assertThat(valid).hasSize(2);
        assertThat(valid).extracting(AiChatMessage::getContent)
                .containsExactly("정상 질문", "정상 응답");
        assertThat(valid).extracting(AiChatMessage::getStatus)
                .containsOnly(AiChatMessage.Status.COMPLETED);
    }
}
