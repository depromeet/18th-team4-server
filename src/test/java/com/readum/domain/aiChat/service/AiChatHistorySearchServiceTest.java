package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.AssembledContext;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.entity.AiChatContextSummaryFixture;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatHistorySearchServiceTest {

    private static final Long SESSION_ID = 7L;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private AiChatContextSummaryRepository aiChatContextSummaryRepository;

    // 결정적 test double: 메시지 토큰 = 내용 글자 수. token_count 미설정 fixture 라 이 fallback 이 쓰인다.
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    private AiChatHistorySearchService serviceWithAssemblyMax(int assemblyMax) {
        AiChatProperties properties = new AiChatProperties(
                new AiChatProperties.Context(assemblyMax, 2000, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(120000, 512),
                new AiChatProperties.Streaming(120, 30, 150, 60, 256)
        );
        return new AiChatHistorySearchService(
                aiChatMessageRepository, aiChatContextSummaryRepository, properties, tokenCounter);
    }

    private void givenNoSummary() {
        given(aiChatContextSummaryRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());
    }

    private void givenSummary(AiChatContextSummary summary) {
        given(aiChatContextSummaryRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(summary));
    }

    private void givenRecentDesc(List<AiChatMessage> recentDesc) {
        given(aiChatMessageRepository.findRecentForContextAssembly(eq(SESSION_ID), any(Pageable.class)))
                .willReturn(recentDesc);
    }

    @Test
    void 요약이_없으면_요약은_null_이고_예산_이내면_전부_시간순으로_반환한다() {
        // newest-first(DESC) 로 반환됨. 합 10 < 최대 토큰 100 → 전부 포함, ASC 로 정렬.
        givenNoSummary();
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "aaaa"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "bbb"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "cc"),
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "d")
        ));

        AssembledContext context = serviceWithAssemblyMax(100).assembleContext(SESSION_ID);

        assertThat(context.summary()).isNull();
        assertThat(context.recentMessages()).hasSize(4);
        assertThat(context.recentMessages().get(0).content()).isEqualTo("d");
        assertThat(context.recentMessages().get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(context.recentMessages().get(3).content()).isEqualTo("aaaa");
    }

    @Test
    void 예산을_넘으면_오래된_턴을_드랍하고_최근_원문_대화는_USER로_시작한다() {
        // 각 3토큰, 최대 토큰 10 → newest 3개(합 9)만 담기고 4번째(12)는 제외.
        // 담긴 3개(newest-first)의 가장 오래된 쪽이 ASSISTANT 면 턴 경계 정렬로 제거된다.
        givenNoSummary();
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "aaa"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "bbb"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "ccc"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "ddd"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "eee"),
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "fff")
        ));

        List<HistoryMessage> history = serviceWithAssemblyMax(10).assembleContext(SESSION_ID).recentMessages();

        // 담김 [id6 ASST, id5 USER, id4 ASST] → 오래된 id4 ASST 제거 → [id5 USER, id6 ASST]
        assertThat(history).hasSize(2);
        assertThat(history.get(0).content()).isEqualTo("bbb");
        assertThat(history.get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(history.get(1).content()).isEqualTo("aaa");
    }

    @Test
    void 마지막_턴은_예산을_넘어도_최소_한_개는_포함한다() {
        // 단일 메시지가 최대 토큰(1) 보다 커도 빈 최근 원문 대화를 만들지 않는다.
        givenNoSummary();
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "xxxxxxxxxx")
        ));

        List<HistoryMessage> history = serviceWithAssemblyMax(1).assembleContext(SESSION_ID).recentMessages();

        assertThat(history).hasSize(1);
        assertThat(history.get(0).content()).isEqualTo("xxxxxxxxxx");
    }

    @Test
    void 빈_세션이면_요약도_null_이고_빈_최근_원문_대화가_반환된다() {
        givenNoSummary();
        givenRecentDesc(List.of());

        AssembledContext context = serviceWithAssemblyMax(8000).assembleContext(SESSION_ID);

        assertThat(context.summary()).isNull();
        assertThat(context.recentMessages()).isEmpty();
    }

    @Test
    void 요약이_있으면_요약을_반환하고_요약_반영_지점_이후_원문만_최근_원문_대화로_싣는다() {
        // 요약 반영 지점(summarized_up_to_message_id)=3 → id 3 이하는 요약이 커버, id 4·5 만 최근 원문 대화.
        givenSummary(AiChatContextSummaryFixture.persisted(100L, SESSION_ID, "누적 요약 본문", 3L, 2, 120));
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(5L, SESSION_ID, "new-assistant"),
                AiChatMessageFixture.persistedUserMessage(4L, SESSION_ID, "new-user"),
                AiChatMessageFixture.persistedAssistantMessage(3L, SESSION_ID, "old-assistant"),
                AiChatMessageFixture.persistedUserMessage(2L, SESSION_ID, "old-user"),
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "older-user")
        ));

        AssembledContext context = serviceWithAssemblyMax(8000).assembleContext(SESSION_ID);

        assertThat(context.summary()).isEqualTo("누적 요약 본문");
        assertThat(context.recentMessages()).hasSize(2);
        assertThat(context.recentMessages().get(0).content()).isEqualTo("new-user");
        assertThat(context.recentMessages().get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(context.recentMessages().get(1).content()).isEqualTo("new-assistant");
    }

    @Test
    void 요약_반영_지점_이후_원문이_없으면_요약만_반환하고_최근_원문_대화는_비어_있다() {
        // 요약 반영 지점=5 로 모든 메시지가 요약에 커버됨 → 최근 원문 대화 없음.
        givenSummary(AiChatContextSummaryFixture.persisted(100L, SESSION_ID, "전부 요약됨", 5L, 3, 90));
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(5L, SESSION_ID, "a"),
                AiChatMessageFixture.persistedUserMessage(4L, SESSION_ID, "b")
        ));

        AssembledContext context = serviceWithAssemblyMax(8000).assembleContext(SESSION_ID);

        assertThat(context.summary()).isEqualTo("전부 요약됨");
        assertThat(context.recentMessages()).isEmpty();
    }
}
