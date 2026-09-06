package com.readum.infrastructure.aiChat.scheduler;

import com.readum.domain.aiChat.service.AiChatExpiredTurnRecoveryService;
import com.readum.domain.aiChat.service.AiChatExpiredTurnRecoveryService.RecoveryReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기한이 지난 미종료 채팅 요청의 복구를 주기적으로 돌린다. 판정·종료는 모두
 * {@link AiChatExpiredTurnRecoveryService} 가 하고, 이 빈은 주기와 요약 로그만 맡는다
 * (컨텍스트 요약 회수기 ContextSummaryJobReaper 와 같은 골격).
 *
 * <p>주기 시작 시점을 늦추지 않는다 — 프로세스가 죽어 남은 예약은 재시작 직후에 돌려주는 편이 낫다.
 * 여러 대가 같은 주기로 돌아 스캔이 겹쳐도 반환은 한 번만 반영된다(행 잠금 + 미종료 확인).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatExpiredTurnScheduler {

    private final AiChatExpiredTurnRecoveryService aiChatExpiredTurnRecoveryService;

    @Scheduled(fixedDelayString = "${ai-chat.turn-recovery.scan-interval-ms}")
    public void recoverOverdueTurnRequests() {
        try {
            RecoveryReport report = aiChatExpiredTurnRecoveryService.recoverOverdueTurnRequests();
            if (report.hasNothingToReport()) {
                return;
            }
            log.warn("기한 지난 채팅 요청 복구 대상={}건 만료 확정={}건 반환 토큰={} 건너뜀={}건 실패={}건",
                    report.scanned(), report.expired(), report.returnedTokens(),
                    report.skipped(), report.failed());
        } catch (RuntimeException scanFailure) {
            // 스캔 자체가 실패해도(예: DB 접속 불가) 다음 주기는 그대로 돈다. 대상 행은 미종료로 남아 있다.
            log.error("기한 지난 채팅 요청 복구 스캔 실패 — 다음 주기에 다시 시도한다", scanFailure);
        }
    }
}
