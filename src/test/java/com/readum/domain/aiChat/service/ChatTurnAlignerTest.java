package com.readum.domain.aiChat.service;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 턴 경계 정렬 규칙(요약 구간은 ASSISTANT 로 끝나고, 최근 원문 대화는 USER 로 시작한다)의 순수 단위 테스트.
 * 한 턴은 USER→ASSISTANT 이므로 경계는 완결된 턴에서 나눠야 반쪽 턴이 안 생긴다.
 */
class ChatTurnAlignerTest {

    private static final Long SESSION_ID = 1L;

    private static AiChatMessage user(long id) {
        return AiChatMessageFixture.persistedUserMessage(id, SESSION_ID, "u" + id);
    }

    private static AiChatMessage assistant(long id) {
        return AiChatMessageFixture.persistedAssistantMessage(id, SESSION_ID, "a" + id);
    }

    // ---- alignSummarizeEndToCompletedTurn: 요약 구간의 끝(endExclusive)을 ASSISTANT 로 맞춘다 ----

    @Test
    void 요약_구간의_끝이_이미_ASSISTANT_면_그대로_둔다() {
        List<AiChatMessage> ascending = List.of(user(1), assistant(2));

        int end = ChatTurnAligner.alignSummarizeEndToCompletedTurn(ascending, 2, 0);

        assertThat(end).isEqualTo(2);
    }

    @Test
    void 요약_구간의_끝이_USER_면_그_USER_를_최근_원문으로_밀어_ASSISTANT_로_끝나게_한다() {
        List<AiChatMessage> ascending = List.of(user(1), assistant(2), user(3));

        int end = ChatTurnAligner.alignSummarizeEndToCompletedTurn(ascending, 3, 0);

        assertThat(end).isEqualTo(2);
    }

    @Test
    void 연속된_USER_는_모두_밀어낸다() {
        List<AiChatMessage> ascending = List.of(assistant(1), user(2), user(3));

        int end = ChatTurnAligner.alignSummarizeEndToCompletedTurn(ascending, 3, 0);

        assertThat(end).isEqualTo(1);
    }

    @Test
    void minEnd_0_이면_전부_USER_일_때_0_까지_내려간다() {
        List<AiChatMessage> ascending = List.of(user(1), user(2));

        int end = ChatTurnAligner.alignSummarizeEndToCompletedTurn(ascending, 2, 0);

        assertThat(end).isEqualTo(0);
    }

    @Test
    void minEnd_1_이면_전부_USER_여도_최소_1_은_남긴다() {
        List<AiChatMessage> ascending = List.of(user(1), user(2));

        int end = ChatTurnAligner.alignSummarizeEndToCompletedTurn(ascending, 2, 1);

        assertThat(end).isEqualTo(1);
    }

    // ---- trimRecentToStartWithUser: 최근 원문(newest-first)의 가장 오래된 쪽 ASSISTANT 를 떼어 USER 로 시작하게 ----

    @Test
    void 최근_원문의_가장_오래된_메시지가_USER_면_그대로_둔다() {
        // newest-first: [a6, u5] → 가장 오래된(마지막) u5 가 USER
        List<AiChatMessage> recentDesc = List.of(assistant(6), user(5));

        List<AiChatMessage> trimmed = ChatTurnAligner.trimRecentToStartWithUser(recentDesc);

        assertThat(trimmed).extracting(AiChatMessage::getId).containsExactly(6L, 5L);
    }

    @Test
    void 최근_원문의_가장_오래된_메시지가_ASSISTANT_면_떼어낸다() {
        // newest-first: [a6, u5, a4] → 가장 오래된 a4(ASSISTANT) 제거 → [a6, u5]
        List<AiChatMessage> recentDesc = List.of(assistant(6), user(5), assistant(4));

        List<AiChatMessage> trimmed = ChatTurnAligner.trimRecentToStartWithUser(recentDesc);

        assertThat(trimmed).extracting(AiChatMessage::getId).containsExactly(6L, 5L);
    }

    @Test
    void 연속된_오래된_ASSISTANT_는_모두_떼어낸다() {
        // newest-first: [u6, a5, a4] → a4 제거 → a5 제거 → [u6]
        List<AiChatMessage> recentDesc = List.of(user(6), assistant(5), assistant(4));

        List<AiChatMessage> trimmed = ChatTurnAligner.trimRecentToStartWithUser(recentDesc);

        assertThat(trimmed).extracting(AiChatMessage::getId).containsExactly(6L);
    }

    @Test
    void 최소_한_개는_남긴다() {
        List<AiChatMessage> recentDesc = List.of(assistant(1));

        List<AiChatMessage> trimmed = ChatTurnAligner.trimRecentToStartWithUser(recentDesc);

        assertThat(trimmed).extracting(AiChatMessage::getId).containsExactly(1L);
    }
}
