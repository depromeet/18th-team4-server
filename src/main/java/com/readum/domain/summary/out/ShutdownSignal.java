package com.readum.domain.summary.out;

/**
 * 애플리케이션이 종료 중인지 조회(읽기 전용). 워커가 작업 선점 전에 확인해, 종료가 시작되면 새 작업을
 * 집지 않는다 — 이미 손에 든 작업만 마치고 끝내기 위해서다.
 *
 * <p>종료가 시작된 뒤에 새로 선점하면 그 작업은 마칠 시간을 못 받는다. 진행 중이던 호출이 끊기면
 * 워커가 이를 일시 오류로 기록해 잘못이 없는 작업에 재시도 벌점이 붙고, 그 사이 종료로 DB 쓰기마저
 * 실패하면 작업이 점유 상태로 남아 lease 만료까지 기다리게 된다.
 *
 * <p>구현은 Spring 컨테이너의 종료 이벤트를 받는다. 워커(domain)가 infrastructure 를 직접 쓰지 못하는
 * 계층 규칙 때문에 Port 로 둔다 — 같은 이유로 존재하는 {@link AiQuotaCooldown} 과 같은 자리다.
 *
 * <p>쓰는 곳이 감상문 워커만은 아니다. 채팅 컨텍스트 요약 워커(domain/aiChat 의 ContextSummaryWorker)도
 * 같은 골격이라 같은 검사를 한다. 특정 기능에 매인 신호가 아니라 앱 전체의 상태를 묻는 것이므로,
 * 두 워커가 공유하는 것이 정상이다 — 바로 옆의 {@link AiQuotaCooldown} 이 이미 같은 모양으로
 * 두 워커에 공유되고 있어 그 배치를 따랐다.
 */
public interface ShutdownSignal {

    /** 지금 애플리케이션이 종료 중인가. */
    boolean isShuttingDown();
}
