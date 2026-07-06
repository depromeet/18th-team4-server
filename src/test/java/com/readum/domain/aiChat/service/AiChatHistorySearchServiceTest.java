package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AiChatHistorySearchServiceTest {

    private static final Long SESSION_ID = 7L;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    // 결정적 test double: 메시지 토큰 = 내용 글자 수. token_count 미설정 fixture 라 이 fallback 이 쓰인다.
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    private AiChatHistorySearchService serviceWithHardCap(int hardCap) {
        AiChatProperties properties = new AiChatProperties(
                new AiChatProperties.Context(hardCap),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(4, 20000, 512),
                new AiChatProperties.TitleGeneration(4, 2000)
        );
        return new AiChatHistorySearchService(aiChatMessageRepository, properties, tokenCounter);
    }

    private void givenRecentDesc(List<AiChatMessage> recentDesc) {
        given(aiChatMessageRepository.findRecentForContextAssembly(eq(SESSION_ID), any(Pageable.class)))
                .willReturn(recentDesc);
    }

    @Test
    void 예산_이내면_전부_시간순으로_반환한다() {
        // newest-first(DESC) 로 반환됨. 합 10 < hard-cap 100 → 전부 포함, ASC 로 정렬.
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "aaaa"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "bbb"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "cc"),
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "d")
        ));

        List<HistoryMessage> history = serviceWithHardCap(100).findPreviousHistory(SESSION_ID);

        assertThat(history).hasSize(4);
        assertThat(history.get(0).content()).isEqualTo("d");
        assertThat(history.get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(history.get(3).content()).isEqualTo("aaaa");
    }

    @Test
    void 예산을_넘으면_오래된_턴을_드랍하고_꼬리는_USER로_시작한다() {
        // 각 3토큰, hard-cap 10 → newest 3개(합 9)만 담기고 4번째(12)는 제외.
        // 담긴 3개(newest-first)의 가장 오래된 쪽이 ASSISTANT 면 턴 경계 정렬로 제거된다.
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "aaa"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "bbb"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "ccc"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "ddd"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "eee"),
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "fff")
        ));

        List<HistoryMessage> history = serviceWithHardCap(10).findPreviousHistory(SESSION_ID);

        // 담김 [id6 ASST, id5 USER, id4 ASST] → 오래된 id4 ASST 제거 → [id5 USER, id6 ASST]
        assertThat(history).hasSize(2);
        assertThat(history.get(0).content()).isEqualTo("bbb");
        assertThat(history.get(0).role()).isEqualTo(HistoryMessage.Role.USER);
        assertThat(history.get(1).content()).isEqualTo("aaa");
    }

    @Test
    void 마지막_턴은_예산을_넘어도_최소_한_개는_포함한다() {
        // 단일 메시지가 hard-cap(1) 보다 커도 빈 꼬리를 만들지 않는다.
        givenRecentDesc(List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "xxxxxxxxxx")
        ));

        List<HistoryMessage> history = serviceWithHardCap(1).findPreviousHistory(SESSION_ID);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).content()).isEqualTo("xxxxxxxxxx");
    }

    @Test
    void 빈_세션이면_빈_history_가_반환된다() {
        givenRecentDesc(List.of());

        List<HistoryMessage> history = serviceWithHardCap(8000).findPreviousHistory(SESSION_ID);

        assertThat(history).isEmpty();
    }
}
