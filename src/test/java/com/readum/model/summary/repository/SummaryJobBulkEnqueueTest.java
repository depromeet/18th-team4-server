package com.readum.model.summary.repository;

import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.SummaryJob;
import com.readum.model.summary.entity.SummaryJobFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새벽 6시 자동 적재의 집합 단위 단일 INSERT({@code enqueuePendingForEligibleSessions}) 검증.
 * 대상 선별 조건(ACTIVE·토큰·최근 메시지)·중복 적재 멱등성·반환 카운트를 확인한다.
 */
@SpringBootTest
@Transactional
class SummaryJobBulkEnqueueTest {

    private static final int MIN_TOKENS = 500;

    @Autowired
    private SummaryJobRepository summaryJobRepository;
    @Autowired
    private AiChatSessionRepository aiChatSessionRepository;
    @Autowired
    private AiChatMessageRepository aiChatMessageRepository;

    private static long userBookSeq = 800_000L;

    private static synchronized long nextUserBookId() {
        return userBookSeq++;
    }

    /** ACTIVE + 토큰 충분 세션을 저장하고, since 이후 COMPLETED 메시지를 단다. 생성된 세션 id 반환. */
    private Long activeSessionWithRecentMessage(int accumulatedTokens, LocalDateTime messageAt) {
        AiChatSession session = aiChatSessionRepository.save(
                AiChatSessionFixture.persistedActiveSession(null, nextUserBookId(), 3, accumulatedTokens, "제목"));
        aiChatMessageRepository.save(
                AiChatMessageFixture.userMessageAt(session.getId(), "최근 대화", messageAt));
        return session.getId();
    }

    @Test
    void 대상_조건을_충족하는_세션은_PENDING_작업으로_적재된다() {
        LocalDateTime now = LocalDateTime.now();
        Long sessionId = activeSessionWithRecentMessage(MIN_TOKENS, now.minusHours(1));

        int enqueued = summaryJobRepository.enqueuePendingForEligibleSessions(
                MIN_TOKENS, now.minusHours(24), now);

        assertThat(enqueued).isEqualTo(1);
        assertThat(summaryJobRepository.existsByActiveSessionId(sessionId)).isTrue();
    }

    @Test
    void 종료된_세션_토큰부족_최근대화없음은_적재_대상에서_제외된다() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(24);

        // 종료(LOCKED) 세션 — 최근 메시지·토큰 충분해도 제외
        AiChatSession locked = aiChatSessionRepository.save(
                AiChatSessionFixture.persistedSummarizedSession(null, nextUserBookId(), 3, MIN_TOKENS, "끝난세션"));
        aiChatMessageRepository.save(
                AiChatMessageFixture.userMessageAt(locked.getId(), "대화", now.minusHours(1)));

        // 토큰 부족 세션 — 임계값 미만
        Long tooFewTokens = activeSessionWithRecentMessage(MIN_TOKENS - 1, now.minusHours(1));

        // 최근 대화 없음 — 메시지가 since 이전
        Long noRecentMessage = activeSessionWithRecentMessage(MIN_TOKENS, now.minusHours(30));

        int enqueued = summaryJobRepository.enqueuePendingForEligibleSessions(MIN_TOKENS, since, now);

        assertThat(enqueued).isZero();
        assertThat(summaryJobRepository.existsByActiveSessionId(locked.getId())).isFalse();
        assertThat(summaryJobRepository.existsByActiveSessionId(tooFewTokens)).isFalse();
        assertThat(summaryJobRepository.existsByActiveSessionId(noRecentMessage)).isFalse();
    }

    @Test
    void 이미_활성_작업이_있는_세션은_중복_적재되지_않는다() {
        LocalDateTime now = LocalDateTime.now();
        Long sessionId = activeSessionWithRecentMessage(MIN_TOKENS, now.minusHours(1));
        // 이미 활성(PENDING) 작업 존재
        summaryJobRepository.saveAndFlush(SummaryJobFixture.persistedPending(null, sessionId, now));

        int enqueued = summaryJobRepository.enqueuePendingForEligibleSessions(
                MIN_TOKENS, now.minusHours(24), now);

        assertThat(enqueued).isZero();
        // 그 세션의 작업은 여전히 1건(중복 생성 안 됨)
        long jobCount = summaryJobRepository.findAll().stream()
                .filter(job -> sessionId.equals(job.getAiChatSessionId()))
                .count();
        assertThat(jobCount).isEqualTo(1);
    }

    @Test
    void 여러_대상_세션을_한_번에_적재하고_반환_카운트가_실제_적재수와_일치한다() {
        LocalDateTime now = LocalDateTime.now();
        Long first = activeSessionWithRecentMessage(MIN_TOKENS, now.minusHours(1));
        Long second = activeSessionWithRecentMessage(MIN_TOKENS + 1000, now.minusHours(2));
        Long third = activeSessionWithRecentMessage(MIN_TOKENS, now.minusMinutes(30));

        int enqueued = summaryJobRepository.enqueuePendingForEligibleSessions(
                MIN_TOKENS, now.minusHours(24), now);

        assertThat(enqueued).isEqualTo(3);
        assertThat(summaryJobRepository.existsByActiveSessionId(first)).isTrue();
        assertThat(summaryJobRepository.existsByActiveSessionId(second)).isTrue();
        assertThat(summaryJobRepository.existsByActiveSessionId(third)).isTrue();
    }

    @Test
    void 적재된_작업은_즉시_처리_가능한_PENDING_상태로_시작한다() {
        // next_attempt_at 을 :now 로 그대로 저장하므로, DATETIME(6) 저장 시 나노초가 마이크로초로
        // 반올림되면 저장값이 캡처한 now 보다 커져 아래 isBeforeOrEqualTo(now) 가 깨진다.
        // 저장·비교 정밀도를 마이크로초로 맞춰 반올림 자체를 없앤다.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Long sessionId = activeSessionWithRecentMessage(MIN_TOKENS, now.minusHours(1));

        summaryJobRepository.enqueuePendingForEligibleSessions(MIN_TOKENS, now.minusHours(24), now);

        SummaryJob enqueued = summaryJobRepository.findAll().stream()
                .filter(job -> sessionId.equals(job.getAiChatSessionId()))
                .findFirst()
                .orElseThrow();
        assertThat(enqueued.getStatus()).isEqualTo(SummaryJob.Status.PENDING);
        assertThat(enqueued.getAttemptCount()).isZero();
        assertThat(enqueued.getNextAttemptAt()).isBeforeOrEqualTo(now);
        assertThat(enqueued.getActiveSessionId()).isEqualTo(sessionId);
    }
}
