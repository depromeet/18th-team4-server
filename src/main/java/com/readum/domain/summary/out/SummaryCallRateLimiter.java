package com.readum.domain.summary.out;

/**
 * 감상문 배치의 OpenAI 호출 속도를 RPM/TPM 한도 아래로 묶는 선제적 페이서(pacer).
 * 단일 인스턴스 가정(in-memory bucket). 다중 인스턴스 전역 페이싱이 필요하면 분산 bucket 으로 교체(Port 유지).
 */
public interface SummaryCallRateLimiter {

    /**
     * 요청 1개 + estimatedTokens 만큼의 예산 확보를 시도한다.
     * 설정된 최대 대기시간 안에 확보하면 true, 못하면 false.
     * false 일 때 호출자는 작업을 다시 큐로 되돌려야 한다(블로킹으로 lease 를 오래 잡지 않기 위함).
     */
    boolean tryAcquire(int estimatedTokens) throws InterruptedException;
}
