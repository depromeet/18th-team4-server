package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatTurnRecoveryProperties;
import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.out.CompletedTurnStore;
import com.readum.model.aiChat.repository.AiChatTurnRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 기한이 지나도록 끝나지 않은 요청을 정리한다 — 실패(EXPIRED)로 확정하고 예약한 토큰을 사용자에게 돌려준다.
 *
 * <p><b>왜 필요한가.</b> 요청 기록은 예약과 같은 트랜잭션으로 커밋되지만, 그 예약을 되돌리는 일은
 * 그 요청을 처리하던 실행이 한다. 프로세스가 죽거나 종료 대기 상한을 넘겨 실행이 사라지면 되돌릴 주체가
 * 없어져, 사용자의 일일 예산이 쓰지도 않은 요청에 묶인 채 남는다. 메모리 진행 목록
 * ({@link AiChatInFlightTurnRegistry})은 프로세스와 함께 사라지므로 반환의 근거가 될 수 없다 —
 * 근거는 DB 의 미종료 행뿐이다.
 *
 * <p><b>돌려주기 전에 완성 답변 보관소를 먼저 본다.</b> 답변이 끝까지 만들어졌는데 저장만 못 한 경우가 있다
 * (프로세스가 죽었거나 DB 가 잠깐 응답하지 않았다). 그때 예약만 돌려주면 <b>돈을 내고 받은 답변을 버리는</b>
 * 셈이라, 기록을 읽어 완성본이 있으면 그대로 확정한다 — 답변 저장·정산·상태 전이를 정상 경로와
 * <b>같은 트랜잭션</b>({@link AiChatTurnOutcomeWriter#finishSuccessfully})으로 처리한다.
 *
 * <p><b>기록을 얼려 두는 절차(봉인)가 없다.</b> 보관소에는 생성이 정상으로 끝난 뒤의 완성본만,
 * 그것도 한 번에 통째로 적히기 때문이다({@link CompletedTurnStore}). 아직 만들어지는 중인 기록이라는 것이
 * 아예 없으므로, 읽는 쪽이 반쯤 쓰인 기록을 완성본으로 오해할 상태가 존재하지 않는다.
 *
 * <p><b>하지 않는 것: 생성 재실행.</b> 기록이 없으면(= 끝까지 가지 못했거나 적지 못했다) 예약을 돌려줄 뿐,
 * 받다 만 답변을 되살리거나 OpenAI 를 다시 부르지 않는다. 부분 답변은 성공으로 만들지 않는다.
 *
 * <p><b>못 읽은 것은 없는 것이 아니다.</b> 보관소가 대답하지 않으면 그 행은 <b>건드리지 않고</b> 다음 회차로
 * 미룬다 — 못 읽었다는 이유로 환불하면, 실제로는 남아 있던 성공 결과를 보지도 않고 버린다.
 *
 * <p><b>시간 경과는 실행이 사라졌다는 증명이 아니다.</b> 기한을 넘겼어도 그 요청의 생성·후처리가 아직
 * 살아 있을 수 있다. 그래서 이 서비스는 "죽었을 것이다" 를 전제로 하지 않고, 종료를 확정하는 순간
 * ({@link AiChatTurnOutcomeWriter#expireIfOverdue})에 행을 잠그고 미종료·기한 초과를 다시 확인한다.
 * 뒤늦게 성공한 실행은 같은 잠금·미종료 확인에 걸려 저장·정산이 DB 에 반영되지 않는다.
 *
 * <p><b>늦은 성공과 겹칠 때 무엇이 최종 판단인가.</b> 기록을 읽은 시점이 아니라 <b>DB 행 잠금과 종료 확인</b>이다.
 * 갈리는 경우는 둘뿐이고 둘 다 한 번만 반영된다.
 * <ul>
 *   <li>읽을 때 기록이 없어 만료로 확정했는데 곧바로 살아 있던 실행이 완성본을 적고 확정을 시도하면,
 *       그 확정이 잠금 뒤 <b>이미 종료됨</b>을 보고 아무것도 반영하지 않는다(청구 없음). 그 실행이 마지막에
 *       기록을 지우는 것도 자기 눈으로 DB 종료를 확인한 뒤다.</li>
 *   <li>읽을 때 완성본이 있어 되살려 확정하는 사이 살아 있던 실행이 같은 답변을 확정하려 하면,
 *       둘 중 먼저 잠근 쪽만 반영되고 다른 쪽은 <b>이미 종료됨</b>을 받는다. 두 쪽이 쓰는 값
 *       (본문·실측 사용량·입력 추정·작성 시각)이 같은 기록에서 나오므로 어느 쪽이 이기든 DB 결과는 같다.</li>
 * </ul>
 * 그래서 같은 답변이 두 번 청구되지도, 같은 예약이 두 번 돌아가지도 않는다.
 *
 * <p><b>겹쳐 돌아도 안전하다.</b> 한 프로세스 안에서 스캔이 겹치든 여러 대가 동시에 돌든, 반환은 행 잠금과
 * 미종료 확인 덕분에 한 번만 반영된다 — 늦게 잠근 쪽은 이미 종료된 행을 보고 건너뛴다.
 * 별도의 분산 잠금이나 선점 표식을 두지 않은 이유다.
 *
 * <p><b>한 행의 실패가 스캔을 멈추지 않는다.</b> 예외는 그 행에서 삼키고 다음 행으로 넘어간다.
 * 끝내지 못한 행은 <b>환불 없이</b> 미종료로 남아 다음 스캔이 다시 집는다 — 재시도 큐를 따로 두지 않는 이유다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatExpiredTurnRecoveryService {

    /**
     * 만료로 끝낸 행의 failure_code 에 남길 표식. 운영에서 "미정산 예약 반환이 정리한 요청" 을 골라 보기 위한 것이며
     * 분기 조건으로 쓰지 않는다(상태 EXPIRED 가 그 역할을 한다).
     */
    static final String EXPIRY_FAILURE_CODE = "EXPIRED_BY_RECOVERY";

    /** 요청 행의 expires_at 이 KST 로 찍히므로(AiChatTurnRequest) 판정 시각도 KST 로 읽는다. */
    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    /**
     * 한 스캔이 읽을 묶음 수의 안전핀. 조회 조건과 종료 조건이 어긋나 같은 목록이 계속 돌아오는 상황에서
     * 스캔이 영영 돌지 않게 막는다. 묶음 100개 × 100건이면 만 건이라, 정상 운영에서 닿을 값이 아니다.
     */
    private static final int MAX_BATCHES_PER_SCAN = 100;

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository;
    private final AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;
    private final AiChatTurnRecoveryProperties recoveryProperties;
    private final CompletedTurnStore completedTurnStore;

    /**
     * 한 번의 스캔 결과. 스케줄러의 요약 로그와 테스트의 단언에 쓴다.
     *
     * @param scanned        훑은 대상 행 수(같은 스캔에서 두 번 집힌 행은 한 번만 센다)
     * @param expired        이번 스캔이 만료로 확정한 행 수
     * @param returnedTokens 그 확정으로 사용자에게 돌려준 예약 토큰 합
     * @param skipped        잠그고 보니 이미 종료됐거나 아직 기한 전이라 건드리지 않은 행 수
     * @param failed         예외로 끝내지 못한 행 수 — 다음 스캔이 다시 집는다
     * @param batches        이번 스캔이 읽은 묶음 수 — 1 보다 크면 밀린 양이 한 묶음을 넘었다는 뜻이다
     * @param recovered      보관소의 완성본으로 되살려 성공 확정한 행 수
     * @param deferred       보관소를 읽지 못해 이번 회차에서 판단을 미룬 행 수 — 환불하지 않았다
     */
    public record RecoveryReport(
            int scanned, int expired, int returnedTokens, int skipped, int failed, int batches,
            int recovered, int deferred) {

        /** 로그로 남길 것이 있는가 — 아무것도 바꾸지 않고 실패도 없었다면 조용히 지나간다. */
        public boolean hasNothingToReport() {
            return expired == 0 && failed == 0 && recovered == 0 && deferred == 0;
        }
    }

    /**
     * 기한이 지난 미종료 요청을 훑어 만료로 확정한다. <b>한 스캔이 밀린 양을 다 비운다</b> —
     * 한 묶음({@code maxRowsPerRun})을 처리한 뒤 묶음이 꽉 찼으면 곧바로 다음 묶음을 읽고,
     * 대상이 묶음보다 적게 나올 때까지 이어 간다. 묶음 크기는 상한이 아니라 한 번에 읽어 오는 행 수,
     * 곧 한 묶음이 만드는 순간 부하를 묶는 값이다. 묶음마다 미루면 밀린 양이 주기당 한 묶음씩만
     * 돌아와, 사용자의 예약이 그만큼 오래 묶인다.
     *
     * <p>판정 기준 시각은 스캔 시작에서 한 번만 계산해 목록 조회와 행 잠금이 같은 기준을 쓰게 한다 —
     * 훑을 때는 대상이었는데 잠글 때는 아니라는 어긋남을 없애기 위해서다. 묶음이 여러 개여도 같은 기준을 쓴다.
     * 스캔 자체는 트랜잭션 밖이고, 트랜잭션은 한 행을 끝낼 때마다 하나씩 열린다. 한 트랜잭션에 다 담으면
     * 먼저 잠근 행들이 스캔이 끝날 때까지 묶여, 정상적으로 끝나려는 늦은 성공까지 기다리게 된다.
     *
     * <p><b>묶음은 id 로 이어 읽는다</b>({@code findOverdueUnfinishedIdsAfter}). 첫 페이지를 되풀이해 읽으면
     * 이번 회차가 끝내지 못하고 남겨 둔 행 — 보관소를 읽지 못해 미룬 행, 잠그고 보니 기한 전이던 행 —
     * 이 그 자리를 계속 차지해 <b>뒤에 있는 행들이 영영 차례를 못 받는다</b>. 앞 묶음의 마지막 id 다음부터
     * 읽으면 그런 굶주림이 생기지 않고, 이미 본 행을 다시 집지도 않는다.
     */
    public RecoveryReport recoverOverdueTurnRequests() {
        LocalDateTime overdueBefore = recoveryProperties.overdueBefore(LocalDateTime.now(ZONE_KST));
        int batchSize = recoveryProperties.maxRowsPerRun();

        Tally tally = new Tally();
        int batchCount = 0;
        long lastSeenId = 0L;
        while (batchCount < MAX_BATCHES_PER_SCAN) {
            List<Long> overdueTurnRequestIds = aiChatTurnRequestRepository.findOverdueUnfinishedIdsAfter(
                    overdueBefore, lastSeenId, PageRequest.of(0, batchSize));
            if (overdueTurnRequestIds.isEmpty()) {
                break;
            }

            batchCount++;
            tally.scanned += overdueTurnRequestIds.size();
            lastSeenId = overdueTurnRequestIds.get(overdueTurnRequestIds.size() - 1);
            for (Long turnRequestId : overdueTurnRequestIds) {
                try {
                    settleOverdueTurn(turnRequestId, overdueBefore, tally);
                } catch (RuntimeException recoveryFailure) {
                    // 한 행의 실패는 여기서 멈춘다. <b>환불하지 않고</b> 미종료로 남겨 다음 스캔이 다시 집는다 —
                    // 되살릴 수 있는 결과가 있었는데 확정만 실패한 경우일 수 있기 때문이다.
                    tally.failed++;
                    log.error("기한 지난 요청 정리 실패 turnRequestId={} — 다음 스캔에서 다시 시도한다",
                            turnRequestId, recoveryFailure);
                }
            }

            if (overdueTurnRequestIds.size() < batchSize) {
                // 묶음이 덜 찼다 = 기준 시각 기준으로 남은 대상을 다 봤다.
                break;
            }
        }
        if (batchCount >= MAX_BATCHES_PER_SCAN) {
            log.warn("미정산 예약 반환이 한 스캔의 묶음 상한 {}개를 채웠다 — 남은 대상은 다음 주기가 이어 집는다"
                    + " (묶음당 {}건)", MAX_BATCHES_PER_SCAN, batchSize);
        }
        return new RecoveryReport(
                tally.scanned, tally.expired, tally.returnedTokens, tally.skipped, tally.failed, batchCount,
                tally.recovered, tally.deferred);
    }

    /**
     * 기한이 지난 요청 하나를 정리한다. <b>보관소를 먼저 읽는 것</b>이 이 메서드의 핵심이다 —
     * 있으면 되살리고, 없으면 만료로 확정하고, 읽지 못했으면 아무것도 하지 않는다.
     */
    private void settleOverdueTurn(Long turnRequestId, LocalDateTime overdueBefore, Tally tally) {
        CompletedTurnStore.Lookup lookup = completedTurnStore.find(turnRequestId);
        if (lookup instanceof CompletedTurnStore.Lookup.Unknown) {
            // 못 읽었다 — 없는 것이 아니다. 이 행은 그대로 두고 다음 회차에 다시 본다.
            tally.deferred++;
            return;
        }
        if (lookup instanceof CompletedTurnStore.Lookup.Found found) {
            recoverCompletedTurn(turnRequestId, found.completedTurn(), tally);
            return;
        }
        expireOverdueTurn(turnRequestId, overdueBefore, tally);
    }

    /**
     * 완성본을 그대로 DB 에 확정한다. 잠금·미종료 확인은 정상 경로와 같은 트랜잭션이 하므로,
     * 살아 있는 생성이 같은 순간에 확정하더라도 한 번만 반영된다.
     *
     * <p><b>확정이 실패하면 예외를 그대로 올려보낸다 — 환불로 넘기지 않는다.</b> 여기까지 온 요청은
     * 끝까지 만들어진 답변이 남아 있다는 뜻이고, 저장·정산이 실패하는 이유는 대개 DB 쪽 일시 문제다.
     * 그것을 "되살릴 수 없다" 로 읽어 예약을 돌려주면, 돈을 내고 받은 답변을 우리 손으로 버리는 셈이 된다.
     * 올려보낸 예외는 스캔의 행별 처리가 받아 이 행을 <b>건드리지 않은 채</b> 다음 회차로 미룬다.
     */
    private void recoverCompletedTurn(
            Long turnRequestId, CompletedTurnStore.CompletedTurn completedTurn, Tally tally) {
        AiChatTurnOutcomeWriter.TurnOutcomeResult result = aiChatTurnOutcomeWriter.finishSuccessfully(
                turnRequestId,
                toGenerationOutcome(completedTurn),
                completedTurn.estimatedInputTokens(),
                completedTurn.generatedAt());
        if (result instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.Succeeded) {
            tally.recovered++;
            log.info("보관된 완성 답변으로 되살려 확정했다 turnRequestId={}", turnRequestId);
        } else {
            // 이미 다른 실행이 끝낸 요청이다 — 저장·정산을 다시 반영하지 않았다.
            tally.skipped++;
        }
        // 여기 도달했으면 DB 에서 종료된 것이 확실하다. 그때에만 기록을 지운다.
        completedTurnStore.delete(turnRequestId);
    }

    /** 되살릴 완성본이 없다 — 기존대로 만료로 확정하고 예약을 돌려준다. */
    private void expireOverdueTurn(Long turnRequestId, LocalDateTime overdueBefore, Tally tally) {
        AiChatTurnOutcomeWriter.TurnOutcomeResult result =
                aiChatTurnOutcomeWriter.expireIfOverdue(turnRequestId, overdueBefore, EXPIRY_FAILURE_CODE);
        if (result instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge finished) {
            tally.expired++;
            tally.returnedTokens += finished.returnedTokens();
            completedTurnStore.delete(turnRequestId);
            return;
        }
        // AlreadyFinished(다른 실행이 이미 먼저 끝냈다) 와 StillRunning(잠그고 보니 기한 전) 둘 다 정상이다.
        tally.skipped++;
        if (result instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.AlreadyFinished) {
            completedTurnStore.delete(turnRequestId);
        }
    }

    /** 보관된 완성본을 정상 경로와 같은 모양의 생성 결과로 옮긴다. 실측 출력이 없으면 여기 오지 않는다. */
    private static AiChatGenerationOutcome toGenerationOutcome(CompletedTurnStore.CompletedTurn completedTurn) {
        return new AiChatGenerationOutcome(
                AiChatGenerationOutcome.Status.SUCCESS,
                completedTurn.content(),
                completedTurn.finishReason(),
                completedTurn.inputTokens(),
                completedTurn.outputTokens(),
                completedTurn.totalTokens());
    }

    /** 한 스캔의 집계. 행마다 늘어나는 값이 여섯이라 지역 변수 여섯을 넘기는 대신 묶는다. */
    private static final class Tally {
        private int scanned;
        private int expired;
        private int returnedTokens;
        private int skipped;
        private int failed;
        private int recovered;
        private int deferred;
    }
}
