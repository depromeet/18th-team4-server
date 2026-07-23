package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.SummaryRange;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 구간 선택기의 순수 단위 테스트. DB·트랜잭션 없이 delta·이전 요약 토큰만으로 구간이 정해진다.
 * 기존 prepareGeneration 의 경계 사례를 그대로 재현해 동작 일치를 증명한다.
 */
class SummaryRangeSelectorTest {

    private static final Long SESSION_ID = 7L;

    // 결정적 test double: 토큰 = 글자 수(null 은 0).
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    private SummaryRangeSelector selectorWith(int keepRecentRawTokens, int maxRequestTokens) {
        AiChatProperties aiChatProperties = new AiChatProperties(
                new AiChatProperties.Context(8000, keepRecentRawTokens, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(4, 20000, 512));
        ContextSummaryJobProperties jobProperties = new ContextSummaryJobProperties(
                2, 2000, 60000, 120, 5, 60, maxRequestTokens);
        return new SummaryRangeSelector(aiChatProperties, jobProperties, tokenCounter);
    }

    @Test
    void 남길_원문이_예산_이하면_요약할_구간이_없다() {
        // 델타 전체 합 6 < keepRecentRawTokens 10 → 요약 구간 없음.
        List<AiChatMessage> delta = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aaa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bbb"));

        SummaryRange range = selectorWith(10, 120000).select(delta, 0);

        assertThat(range.isEmpty()).isTrue();
    }

    @Test
    void keep_만큼_남기고_요약_구간은_ASSISTANT_로_끝나게_정렬된다() {
        // 각 2토큰, keep=6. newest 부터 6 채우면 id4·5·6 남김, 요약 후보 [id1 U, id2 A, id3 U].
        // 턴 정렬로 id3 U 를 최근 원문으로 밀어 [id1 U, id2 A] 만 요약.
        List<AiChatMessage> delta = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "11"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "22"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "33"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "44"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "55"),
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "66"));

        SummaryRange range = selectorWith(6, 120000).select(delta, 0);

        assertThat(range.isEmpty()).isFalse();
        assertThat(range.messagesToSummarize()).extracting(AiChatMessage::getId).containsExactly(1L, 2L);
        assertThat(range.lastSummarizedMessageId()).isEqualTo(2L);
    }

    @Test
    void 요약_대상이_한_호출_예산을_넘으면_오래된_쪽부터_잘라_부분_전진한다() {
        // maxRequestTokens=810, 이전 요약 0, 출력추정 800 → 청크 예산 10.
        // keep=2 라 최근 원문은 [id5,id6], 요약 후보는 [id1..id4]. 청크 예산 10 → id1(4)+id2(4)=8, id3 추가 시 12>10 → [id1,id2] 만.
        List<AiChatMessage> delta = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aaaa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bbbb"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "cccc"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "dddd"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "ee"),
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "ff"));

        SummaryRange range = selectorWith(2, 810).select(delta, 0);

        assertThat(range.messagesToSummarize()).extracting(AiChatMessage::getId).containsExactly(1L, 2L);
        assertThat(range.lastSummarizedMessageId()).isEqualTo(2L);
    }

    @Test
    void 첫_완결_턴조차_청크_예산에_못_담으면_반쪽_USER_를_요약하지_않고_none_을_반환한다() {
        // maxRequestTokens=805, 출력추정 800 → 청크 예산 5. keep=2 라 요약 후보 [id1 U, id2 A, id3 U, id4 A].
        // id1(2) 은 담기지만 id2(A,20) 를 더하면 22>5 → 예산만으론 [id1] 반쪽 USER. 이를 요약하면 답변 id2 가
        // 조립 시 최근 원문 시작 정렬에 잘려 요약에도 raw 에도 남지 않는다. 첫 완결 턴이 예산을 넘는다는 건 상한(805)도
        // 넘는다는 뜻(청크 예산 = 상한 − 이전요약 − 출력)이라 요약해봐야 워커 fail-fast — 진전 없이 none 을 반환한다.
        List<AiChatMessage> delta = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bbbbbbbbbbbbbbbbbbbb"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "cc"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "dd"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "ee"),
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "ff"));

        SummaryRange range = selectorWith(2, 805).select(delta, 0);

        assertThat(range.isEmpty()).isTrue();
    }

    @Test
    void 이전_요약_토큰이_크면_청크_예산이_줄어_더_적게_요약한다() {
        // maxRequestTokens=820, 출력추정 800, 이전 요약 6 → 청크 예산 820-800-6=14.
        // keep=2 라 요약 후보 [id1..id4](각 4). id1(4)+id2(4)+id3(4)=12, id4 추가 시 16>14 → [id1,id2,id3] 후보.
        // 턴 정렬: id3 는 USER → 밀어 [id1,id2] 만 요약(ASSISTANT 로 끝).
        List<AiChatMessage> delta = List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aaaa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bbbb"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "cccc"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "dddd"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "ee"),
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "ff"));

        SummaryRange range = selectorWith(2, 820).select(delta, 6);

        assertThat(range.messagesToSummarize()).extracting(AiChatMessage::getId).containsExactly(1L, 2L);
    }
}
