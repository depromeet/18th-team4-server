package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.config.AiChatTurnRecoveryProperties;
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
 * ({@link AiChatInFlightTurnRegistry})은 프로세스와 함께 사라지므로 복구의 근거가 될 수 없다 —
 * 근거는 DB 의 미종료 행뿐이다.
 *
 * <p><b>하지 않는 것: 생성 재실행.</b> 만료는 "이 요청을 더 기다리지 않고 예약을 돌려준다" 는 정산 결정이다.
 * 받다 만 답변을 되살리거나 OpenAI 를 다시 부르지 않는다. 사용자가 답변을 원하면 새 요청 식별자로 다시 보낸다.
 *
 * <p><b>시간 경과는 실행이 사라졌다는 증명이 아니다.</b> 기한을 넘겼어도 그 요청의 생성·후처리가 아직
 * 살아 있을 수 있다. 그래서 이 서비스는 "죽었을 것이다" 를 전제로 하지 않고, 종료를 확정하는 순간
 * ({@link AiChatTurnOutcomeWriter#expireIfOverdue})에 행을 잠그고 미종료·기한 초과를 다시 확인한다.
 * 뒤늦게 성공한 실행은 같은 잠금·미종료 확인에 걸려 저장·정산이 DB 에 반영되지 않는다.
 *
 * <p><b>겹쳐 돌아도 안전하다.</b> 한 프로세스 안에서 스캔이 겹치든 여러 대가 동시에 돌든, 반환은 행 잠금과
 * 미종료 확인 덕분에 한 번만 반영된다 — 늦게 잠근 쪽은 이미 종료된 행을 보고 건너뛴다.
 * 별도의 분산 잠금이나 선점 표식을 두지 않은 이유다.
 *
 * <p><b>한 행의 실패가 스캔을 멈추지 않는다.</b> 예외는 그 행에서 삼키고 다음 행으로 넘어간다.
 * 끝내지 못한 행은 미종료로 남으므로 다음 스캔이 다시 집는다 — 재시도 큐를 따로 두지 않는 이유다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatExpiredTurnRecoveryService {

    /**
     * 만료로 끝낸 행의 failure_code 에 남길 표식. 운영에서 "복구가 정리한 요청" 을 골라 보기 위한 것이며
     * 분기 조건으로 쓰지 않는다(상태 EXPIRED 가 그 역할을 한다).
     */
    static final String EXPIRY_FAILURE_CODE = "EXPIRED_BY_RECOVERY";

    /** 요청 행의 expires_at 이 KST 로 찍히므로(AiChatTurnRequest) 판정 시각도 KST 로 읽는다. */
    private static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    private final AiChatTurnRequestRepository aiChatTurnRequestRepository;
    private final AiChatTurnOutcomeWriter aiChatTurnOutcomeWriter;
    private final AiChatTurnRecoveryProperties recoveryProperties;

    /**
     * 한 번의 스캔 결과. 스케줄러의 요약 로그와 테스트의 단언에 쓴다.
     *
     * @param scanned        훑은 대상 행 수(상한까지)
     * @param expired        이번 스캔이 만료로 확정한 행 수
     * @param returnedTokens 그 확정으로 사용자에게 돌려준 예약 토큰 합
     * @param skipped        잠그고 보니 이미 종료됐거나 아직 기한 전이라 건드리지 않은 행 수
     * @param failed         예외로 끝내지 못한 행 수 — 다음 스캔이 다시 집는다
     */
    public record RecoveryReport(int scanned, int expired, int returnedTokens, int skipped, int failed) {

        /** 로그로 남길 것이 있는가 — 아무것도 만료시키지 않고 실패도 없었다면 조용히 지나간다. */
        public boolean hasNothingToReport() {
            return expired == 0 && failed == 0;
        }
    }

    /**
     * 기한이 지난 미종료 요청을 상한만큼 훑어 만료로 확정한다.
     *
     * <p>판정 기준 시각은 스캔 시작에서 한 번만 계산해 목록 조회와 행 잠금이 같은 기준을 쓰게 한다 —
     * 훑을 때는 대상이었는데 잠글 때는 아니라는 어긋남을 없애기 위해서다.
     * 스캔 자체는 트랜잭션 밖이고, 트랜잭션은 한 행을 끝낼 때마다 하나씩 열린다. 한 트랜잭션에 다 담으면
     * 먼저 잠근 행들이 스캔이 끝날 때까지 묶여, 정상적으로 끝나려는 늦은 성공까지 기다리게 된다.
     */
    public RecoveryReport recoverOverdueTurnRequests() {
        LocalDateTime overdueBefore = recoveryProperties.overdueBefore(LocalDateTime.now(ZONE_KST));
        List<Long> overdueTurnRequestIds = aiChatTurnRequestRepository.findOverdueUnfinishedIds(
                overdueBefore, PageRequest.of(0, recoveryProperties.maxRowsPerRun()));
        if (overdueTurnRequestIds.isEmpty()) {
            return new RecoveryReport(0, 0, 0, 0, 0);
        }

        int expiredCount = 0;
        int returnedTokens = 0;
        int skippedCount = 0;
        int failedCount = 0;
        for (Long turnRequestId : overdueTurnRequestIds) {
            try {
                AiChatTurnOutcomeWriter.TurnOutcomeResult result =
                        aiChatTurnOutcomeWriter.expireIfOverdue(
                                turnRequestId, overdueBefore, EXPIRY_FAILURE_CODE);
                if (result instanceof AiChatTurnOutcomeWriter.TurnOutcomeResult.FinishedWithoutCharge finished) {
                    expiredCount++;
                    returnedTokens += finished.returnedTokens();
                } else {
                    // AlreadyFinished(다른 실행이 먼저 끝냈다) 와 StillRunning(잠그고 보니 기한 전) 둘 다
                    // 정상이다 — 이번 스캔이 건드릴 행이 아니었을 뿐이다.
                    skippedCount++;
                }
            } catch (RuntimeException recoveryFailure) {
                // 한 행의 실패는 여기서 멈춘다. 그 행은 미종료로 남아 다음 스캔이 다시 집는다.
                failedCount++;
                log.error("만료 요청 복구 실패 turnRequestId={} — 다음 스캔에서 다시 시도한다",
                        turnRequestId, recoveryFailure);
            }
        }
        return new RecoveryReport(
                overdueTurnRequestIds.size(), expiredCount, returnedTokens, skippedCount, failedCount);
    }
}
