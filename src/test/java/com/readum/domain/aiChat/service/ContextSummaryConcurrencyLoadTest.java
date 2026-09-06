package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AssembledContext;
import com.readum.domain.aiChat.dto.ContextSummaryResult;
import com.readum.domain.aiChat.out.AiContextSummaryClient;
import com.readum.model.aiChat.entity.AiChatContextSummary;
import com.readum.model.aiChat.entity.AiChatContextSummaryJob;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.repository.AiChatContextSummaryJobRepository;
import com.readum.model.aiChat.repository.AiChatContextSummaryRepository;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 컨텍스트 요약 파이프라인의 동시성/정합성 부하 테스트.
 *
 * <p>여러 세션을 동시에 요약 임계값 이상으로 채우고, 여러 워커 스레드를 동시에 굴려
 * 실제 위험 지점(트리거 멱등 적재·SKIP LOCKED 선점 경합·세션당 활성 job unique·낙관적 version)을 부하로 검증한다.
 * LLM(AiContextSummaryClient)만 스텁한다 — OpenAI 호출은 위험 지점이 아니라 외부 의존이며, 실 키·과금 없이 파이프라인을 돌리기 위함.
 *
 * <p>H2 한계: SKIP LOCKED 의 "동시 skip" 은 H2 에서 성립하지 않을 수 있어(직렬화될 수 있음),
 * 이 테스트가 검증하는 것은 MySQL 성능이 아니라 <b>정합성 불변식</b>(세션당 요약 1개·요약 반영 지점 단조·중복 처리 없음)이다.
 * MySQL 전용 SKIP LOCKED 동작은 프로덕션에서 검증된 summary_job 큐 패턴을 그대로 복제했다.
 */
@SpringBootTest
class ContextSummaryConcurrencyLoadTest {

    private static final int SESSION_COUNT = 5;
    private static final long SESSION_ID_BASE = 950_000L;
    private static final int TOKENS_PER_MESSAGE = 1000; // 6개 × 1000 = 6000 > 트리거 4000

    @MockitoBean
    private AiContextSummaryClient aiContextSummaryClient;

    @Autowired
    private AiChatMessageRepository messageRepository;
    @Autowired
    private AiChatContextSummaryRepository summaryRepository;
    @Autowired
    private AiChatContextSummaryJobRepository jobRepository;
    @Autowired
    private EnqueueContextSummaryJobService enqueueService;
    @Autowired
    private ContextSummaryWorker worker;
    @Autowired
    private AiChatHistorySearchService historySearchService;

    private final AtomicInteger summaryCallCount = new AtomicInteger(0);

    @BeforeEach
    void stubLlm() {
        given(aiContextSummaryClient.generate(any(), any())).willAnswer(invocation -> {
            summaryCallCount.incrementAndGet();
            return new ContextSummaryResult("[누적 요약] 세션 대화를 압축한 결과");
        });
    }

    @Test
    void 동시_세션_5개를_임계값_초과시키고_동시_워커로_요약하면_세션당_정확히_한_번씩_요약_반영_지점_정합하게_생성된다() throws Exception {
        List<Long> sessionIds = new ArrayList<>();
        for (int i = 0; i < SESSION_COUNT; i++) {
            sessionIds.add(SESSION_ID_BASE + i);
        }

        // 1) 각 세션에 U/A ×3 (6000 토큰) 대화를 커밋한다.
        for (Long sessionId : sessionIds) {
            seedConversation(sessionId, 3);
        }

        // 2) 동시 트리거 적재 — 세션당 3스레드가 동시에 enqueue 를 때려 멱등성(활성 job 1개)을 검증한다.
        runConcurrently(SESSION_COUNT * 3, index -> {
            Long sessionId = sessionIds.get(index % SESSION_COUNT);
            enqueueService.enqueueIfRecentMessagesExceedThreshold(sessionId);
        });
        long enqueuedJobs = jobRepository.findAll().stream()
                .filter(job -> sessionIds.contains(job.getSessionId()))
                .count();
        assertThat(enqueuedJobs)
                .as("세션당 활성 job 1개 — 동시 적재해도 unique 로 멱등")
                .isEqualTo(SESSION_COUNT);

        // 3) 동시 워커 6개로 드레인 — SKIP LOCKED 선점 경합.
        int workerThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workerThreads);
        for (int i = 0; i < workerThreads; i++) {
            pool.submit(() -> worker.processUntilEmpty());
        }

        // 4) 모든 세션에 요약이 생기고 모든 job 이 SUCCEEDED 될 때까지 대기.
        await().atMost(Duration.ofSeconds(30)).until(() ->
                sessionIds.stream().allMatch(id -> summaryRepository.findBySessionId(id).isPresent())
                        && jobRepository.findAll().stream()
                                .filter(job -> sessionIds.contains(job.getSessionId()))
                                .allMatch(job -> job.getStatus() == AiChatContextSummaryJob.Status.SUCCEEDED));
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // 5) 세션별 불변식 검증.
        for (Long sessionId : sessionIds) {
            List<AiChatMessage> messages = messageRepository.findCompletedMessagesAfter(sessionId, 0L);
            AiChatContextSummary summary = summaryRepository.findBySessionId(sessionId).orElseThrow();

            assertThat(summary.getVersion()).as("첫 라운드 → version 1").isEqualTo(1);
            assertThat(summary.getSummarizedUpToMessageId()).as("요약 반영 지점은 진전(>0)").isPositive();

            AiChatMessage lastSummarizedMessage = messages.stream()
                    .filter(m -> m.getId().equals(summary.getSummarizedUpToMessageId()))
                    .findFirst().orElseThrow();
            assertThat(lastSummarizedMessage.getRole())
                    .as("요약 반영 지점은 항상 완결된 턴의 끝(ASSISTANT)")
                    .isEqualTo(AiChatMessage.Role.ASSISTANT);

            // 조립기: 요약 + 요약 반영 지점 이후 원문만 최근 원문 대화로.
            AssembledContext assembled = historySearchService.assembleContext(sessionId);
            assertThat(assembled.summary()).isEqualTo("[누적 요약] 세션 대화를 압축한 결과");
            assertThat(assembled.recentMessages()).isNotEmpty();
        }

        // 세션당 활성 job 이 1개였으니 요약 LLM 호출도 세션 수만큼(중복 처리 없음).
        assertThat(summaryCallCount.get())
                .as("중복 처리 없음 — 세션 수만큼만 요약 생성")
                .isEqualTo(SESSION_COUNT);
    }

    @Test
    void 요약된_세션에_대화를_더_쌓고_다시_요약하면_요약_반영_지점이_단조_증가하고_version_이_오른다() throws Exception {
        Long sessionId = SESSION_ID_BASE + 100;

        seedConversation(sessionId, 3);
        enqueueService.enqueueIfRecentMessagesExceedThreshold(sessionId);
        drainUntilNoJobs(sessionId);
        AiChatContextSummary firstRound = summaryRepository.findBySessionId(sessionId).orElseThrow();

        // 대화를 더 쌓아 요약 반영 지점 이후 최근 원문 대화를 다시 임계값 이상으로.
        seedConversation(sessionId, 3);
        enqueueService.enqueueIfRecentMessagesExceedThreshold(sessionId);
        drainUntilNoJobs(sessionId);
        AiChatContextSummary secondRound = summaryRepository.findBySessionId(sessionId).orElseThrow();

        assertThat(secondRound.getVersion()).isEqualTo(firstRound.getVersion() + 1);
        assertThat(secondRound.getSummarizedUpToMessageId())
                .as("요약 반영 지점은 단조 증가")
                .isGreaterThan(firstRound.getSummarizedUpToMessageId());
    }

    /** 세션에 U/A 한 쌍을 turnCount 번 커밋한다. 각 메시지 token_count = TOKENS_PER_MESSAGE. */
    private void seedConversation(Long sessionId, int turnCount) {
        for (int t = 0; t < turnCount; t++) {
            messageRepository.saveAndFlush(AiChatMessage.createUserMessage(
                    sessionId, "질문 " + t, TOKENS_PER_MESSAGE));
            messageRepository.saveAndFlush(AiChatMessage.createAssistantSuccess(
                    sessionId, "답변 " + t, 10, TOKENS_PER_MESSAGE, 10 + TOKENS_PER_MESSAGE, TOKENS_PER_MESSAGE));
        }
    }

    private void drainUntilNoJobs(Long sessionId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            worker.processUntilEmpty();
            return jobRepository.findAll().stream()
                    .filter(job -> job.getSessionId().equals(sessionId))
                    .allMatch(job -> job.getStatus() == AiChatContextSummaryJob.Status.SUCCEEDED
                            || job.getStatus() == AiChatContextSummaryJob.Status.FAILED);
        });
    }

    private void runConcurrently(int taskCount, java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(taskCount, 12));
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.accept(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown(); // 동시에 출발
        done.await(20, TimeUnit.SECONDS);
        pool.shutdownNow();
    }
}
