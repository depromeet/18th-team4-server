package com.readum.domain.summary.out;

/**
 * 감상문 배치의 OpenAI 호출 속도를 RPM/TPM 한도 아래로 묶는 선제적 페이서(pacer).
 * 호출 직전 요청 1개와 예상 토큰만큼의 예산이 확보될 때까지 블로킹 대기시켜, 새벽 폭주로 인한 429 를 미리 줄인다.
 * 단일 인스턴스 가정(in-memory bucket). 다중 인스턴스 전역 페이싱이 필요하면 분산 bucket 으로 교체(Port 유지).
 */
public interface SummaryCallRateLimiter {

    /** 요청 1개 + estimatedTokens 만큼의 예산이 확보될 때까지 대기한 뒤 통과한다. */
    void acquire(int estimatedTokens) throws InterruptedException;
}
