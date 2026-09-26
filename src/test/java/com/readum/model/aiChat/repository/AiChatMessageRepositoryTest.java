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
    @Test
    @DisplayName("되살려 늦게 저장한 답변은 생성 시각으로 정렬되고, id 기준 요약 대상에서도 빠지지 않는다")
    void 되살린_답변의_순서와_요약_대상_포함() {
        // 되살리기는 답변을 <b>생성된 시각</b>으로 저장한다. 그래서 id 는 가장 큰데 createdAt 은 앞선다 —
        // 그 두 성질이 각각 어디에 쓰이는지를 한 번에 못박는다.
        Long sessionId = nextSessionId();
        AiChatMessage firstUser = aiChatMessageRepository.save(
                AiChatMessage.createUserMessage(sessionId, "첫 질문"));
        AiChatMessage laterUser = aiChatMessageRepository.save(
                AiChatMessage.createUserMessage(sessionId, "그 다음 질문"));
        AiChatMessage recovered = aiChatMessageRepository.save(AiChatMessage.createAssistantSuccess(
                sessionId, "되살린 답변", 10, 5, 15, 5,
                laterUser.getCreatedAt().minusMinutes(1)));
        aiChatMessageRepository.flush();

        // 1) 화면 순서는 저장 순서가 아니라 생성 시각이 정한다 — id 가 가장 큰 되살린 답변이 맨 뒤로 간다.
        List<AiChatMessage> visible = aiChatMessageRepository
                .findVisibleHistory(sessionId, PageRequest.of(0, 10)).getContent();
        assertThat(visible).extracting(AiChatMessage::getId).contains(recovered.getId());
        assertThat(visible.get(visible.size() - 1).getId())
                .as("저장 시각으로 적었다면 맨 앞에 왔을 것이다")
                .isEqualTo(recovered.getId());
        assertThat(recovered.getId())
                .isGreaterThan(firstUser.getId())
                .isGreaterThan(laterUser.getId());

        // 2) 요약 반영 지점은 id 기준이라, 늦게 저장된 답변은 더 큰 id 를 받아 다음 요약에서 잡힌다.
        List<AiChatMessage> afterWatermark =
                aiChatMessageRepository.findCompletedMessagesAfter(sessionId, laterUser.getId());
        assertThat(afterWatermark).extracting(AiChatMessage::getId)
                .as("되살린 답변이 요약에서 빠지면 그 턴이 컨텍스트에서 사라진다")
                .containsExactly(recovered.getId());
    }
}
