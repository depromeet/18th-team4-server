package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatProperties;
import com.readum.domain.aiChat.config.ContextSummaryJobProperties;
import com.readum.domain.aiChat.dto.ContextSummaryGenerationContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.entity.AiChatContextSummaryFixture;
import com.readum.model.aiChat.entity.AiChatContextSummaryJob;
import com.readum.model.aiChat.entity.AiChatContextSummaryJobFixture;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContextSummaryJobLifecycleServiceTest {

    private static final Long SESSION_ID = 7L;
    private static final Long JOB_ID = 42L;
    private static final String OWNER = "owner-uuid";

    @Mock
    private AiChatContextSummaryJobRepository jobRepository;
    @Mock
    private AiChatContextSummaryRepository summaryRepository;
    @Mock
    private AiChatMessageRepository messageRepository;

    // 결정적 test double: 토큰 = 글자 수(null 은 0).
    private final TokenCounter tokenCounter = text -> text == null ? 0 : text.length();

    private ContextSummaryJobLifecycleService serviceWithRecentBudget(int recentRawTokenBudget) {
        AiChatProperties aiChatProperties = new AiChatProperties(
                new AiChatProperties.Context(8000, recentRawTokenBudget, 4000, 800),
                new AiChatProperties.MessageRule(1000),
                new AiChatProperties.RateLimit(10, 5),
                new AiChatProperties.TokenBudget(4, 20000, 512),
                new AiChatProperties.TitleGeneration(4, 2000));
        ContextSummaryJobProperties jobProperties = new ContextSummaryJobProperties(
                2, 2000, 60000, 120, 5, 60, 120000);
        return new ContextSummaryJobLifecycleService(
                jobRepository, summaryRepository, messageRepository, jobProperties, aiChatProperties, tokenCounter);
    }

    private void givenOwnedProcessingJob() {
        AiChatContextSummaryJob job = AiChatContextSummaryJobFixture.persistedProcessing(
                JOB_ID, SESSION_ID, OWNER, LocalDateTime.now().plusMinutes(2), 0);
        given(jobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
    }

    @Test
    void 남길_원문이_예산_이하면_요약할_구간이_없어_null_을_반환하고_작업을_성공_종료한다() {
        givenOwnedProcessingJob();
        given(summaryRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());
        // 델타 전체 합 6 < recentRawTokenBudget 10 → 요약 구간 없음.
        given(messageRepository.findCompletedAfterIdAsc(SESSION_ID, 0L)).willReturn(List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "aaa"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "bbb")
        ));

        ContextSummaryGenerationContext context = serviceWithRecentBudget(10).prepareGeneration(JOB_ID, OWNER);

        assertThat(context).isNull();
    }

    @Test
    void 요약_범위는_recent_budget_만큼_남기고_경계는_ASSISTANT_로_정렬된다() {
        givenOwnedProcessingJob();
        given(summaryRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());
        // 각 2토큰, recentRawTokenBudget=6. newest 부터 6 채우면 id4·5·6 이 원문 꼬리, split 은 id4(index3).
        // 요약 후보 [id1 U, id2 A, id3 U] → 경계 정렬로 꼬리 시작이 USER 가 되도록 id3 U 를 꼬리로 밀어 [id1 U, id2 A] 만 요약.
        given(messageRepository.findCompletedAfterIdAsc(SESSION_ID, 0L)).willReturn(List.of(
                AiChatMessageFixture.persistedUserMessage(1L, SESSION_ID, "11"),
                AiChatMessageFixture.persistedAssistantMessage(2L, SESSION_ID, "22"),
                AiChatMessageFixture.persistedUserMessage(3L, SESSION_ID, "33"),
                AiChatMessageFixture.persistedAssistantMessage(4L, SESSION_ID, "44"),
                AiChatMessageFixture.persistedUserMessage(5L, SESSION_ID, "55"),
                AiChatMessageFixture.persistedAssistantMessage(6L, SESSION_ID, "66")
        ));

        ContextSummaryGenerationContext context = serviceWithRecentBudget(6).prepareGeneration(JOB_ID, OWNER);

        assertThat(context).isNotNull();
        assertThat(context.deltaToSummarize()).extracting(AiChatMessage::getId).containsExactly(1L, 2L);
        assertThat(context.newBoundaryMessageId()).isEqualTo(2L);
        assertThat(context.previousVersion()).isNull();
        assertThat(context.previousSummaryContent()).isNull();
    }

    @Test
    void 첫_요약이면_요약_행을_새로_저장한다() {
        AiChatContextSummaryJob job = AiChatContextSummaryJobFixture.persistedProcessing(
                JOB_ID, SESSION_ID, OWNER, LocalDateTime.now().plusMinutes(2), 0);
        given(jobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
        given(summaryRepository.findBySessionIdForUpdate(SESSION_ID)).willReturn(Optional.empty());
        ContextSummaryGenerationContext context = new ContextSummaryGenerationContext(
                SESSION_ID, null, null, List.of(), 5L);

        serviceWithRecentBudget(10).recordSuccess(JOB_ID, OWNER, context, new ContextSummaryResult("새 요약"));

        verify(summaryRepository).save(any(AiChatContextSummary.class));
        assertThat(job.getStatus()).isEqualTo(AiChatContextSummaryJob.Status.SUCCEEDED);
    }

    @Test
    void 낙관적_충돌이면_요약을_갱신하지_않고_작업만_성공_종료한다() {
        AiChatContextSummaryJob job = AiChatContextSummaryJobFixture.persistedProcessing(
                JOB_ID, SESSION_ID, OWNER, LocalDateTime.now().plusMinutes(2), 0);
        given(jobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
        // 준비 땐 version=1 이었는데, 지금 요약 행은 version=2 로 이미 다른 워커가 갱신함 → 폐기.
        AiChatContextSummary current = AiChatContextSummaryFixture.persisted(100L, SESSION_ID, "최신 요약", 20L, 2, 80);
        given(summaryRepository.findBySessionIdForUpdate(SESSION_ID)).willReturn(Optional.of(current));
        ContextSummaryGenerationContext staleContext = new ContextSummaryGenerationContext(
                SESSION_ID, "낡은 요약", 1, List.of(), 15L);

        serviceWithRecentBudget(10).recordSuccess(JOB_ID, OWNER, staleContext, new ContextSummaryResult("덮어쓸 뻔한 요약"));

        assertThat(current.getContent()).isEqualTo("최신 요약");
        assertThat(current.getVersion()).isEqualTo(2);
        assertThat(current.getSummarizedUntilMessageId()).isEqualTo(20L);
        verify(summaryRepository, never()).save(any());
        assertThat(job.getStatus()).isEqualTo(AiChatContextSummaryJob.Status.SUCCEEDED);
    }

    @Test
    void 경계가_역행하면_요약을_갱신하지_않는다() {
        AiChatContextSummaryJob job = AiChatContextSummaryJobFixture.persistedProcessing(
                JOB_ID, SESSION_ID, OWNER, LocalDateTime.now().plusMinutes(2), 0);
        given(jobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
        // version 은 일치(1)하지만 새 경계(15)가 현재 경계(20)보다 작다(역행) → 폐기.
        AiChatContextSummary current = AiChatContextSummaryFixture.persisted(100L, SESSION_ID, "요약", 20L, 1, 80);
        given(summaryRepository.findBySessionIdForUpdate(SESSION_ID)).willReturn(Optional.of(current));
        ContextSummaryGenerationContext regressingContext = new ContextSummaryGenerationContext(
                SESSION_ID, "요약", 1, List.of(), 15L);

        serviceWithRecentBudget(10).recordSuccess(JOB_ID, OWNER, regressingContext, new ContextSummaryResult("역행 요약"));

        assertThat(current.getContent()).isEqualTo("요약");
        assertThat(current.getVersion()).isEqualTo(1);
        assertThat(current.getSummarizedUntilMessageId()).isEqualTo(20L);
        assertThat(job.getStatus()).isEqualTo(AiChatContextSummaryJob.Status.SUCCEEDED);
    }

    @Test
    void version_이_일치하고_경계가_전진하면_요약을_갱신한다() {
        AiChatContextSummaryJob job = AiChatContextSummaryJobFixture.persistedProcessing(
                JOB_ID, SESSION_ID, OWNER, LocalDateTime.now().plusMinutes(2), 0);
        given(jobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
        AiChatContextSummary current = AiChatContextSummaryFixture.persisted(100L, SESSION_ID, "이전 요약", 10L, 1, 80);
        given(summaryRepository.findBySessionIdForUpdate(SESSION_ID)).willReturn(Optional.of(current));
        ContextSummaryGenerationContext context = new ContextSummaryGenerationContext(
                SESSION_ID, "이전 요약", 1, List.of(), 20L);

        serviceWithRecentBudget(10).recordSuccess(JOB_ID, OWNER, context, new ContextSummaryResult("갱신된 요약"));

        assertThat(current.getContent()).isEqualTo("갱신된 요약");
        assertThat(current.getVersion()).isEqualTo(2);
        assertThat(current.getSummarizedUntilMessageId()).isEqualTo(20L);
        assertThat(job.getStatus()).isEqualTo(AiChatContextSummaryJob.Status.SUCCEEDED);
    }
}
