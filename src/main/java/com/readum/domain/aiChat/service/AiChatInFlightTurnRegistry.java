package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 이 프로세스가 지금 처리 중인 채팅 턴 목록. 배포·재시작 때 진행 중인 생성·후처리가 잘려나가지 않도록
 * 종료 대기의 기준이 되고, 운영 중에는 어떤 요청이 얼마나 오래 매달려 있는지 확인하는 창구가 된다.
 *
 * <p><b>추적 구간은 "턴" 하나다</b> — 선행 처리 시작부터 마지막 후처리(저장·정산 또는 실패 보상)가
 * 이번 실행에서 끝날 때까지다. 생성 수신이 끝난 시점이 아니라 후처리가 끝난 시점에 목록에서 빠진다.
 * 후처리가 아직 실행기에 제출조차 되지 않은 턴(생성 중인 턴)도 목록에 남아 있으므로,
 * 실행기에 제출된 작업 수나 살아 있는 스레드 수로 완료를 판단할 때 생기는 누락이 없다.
 *
 * <p><b>SSE 전달의 종료는 이 목록의 조건이 아니다.</b> 사용자 연결이 먼저 끊겨도 생성·후처리는 계속되고,
 * 반대로 전달이 아직 남아 있어도 후처리가 끝났으면 이 목록에서는 빠진다. 살아 있는 SSE 요청을 기다리는
 * 일은 웹 서버의 graceful shutdown 이 따로 한다.
 *
 * <p><b>DB 요청 기록을 대신하지 않는다.</b> 이 목록은 JVM 메모리에만 있어서 프로세스가 강제 종료되면
 * 그대로 사라진다. 종료 대기 기한 안에 끝내지 못한 턴의 예약(토큰 예산)은 DB 의 미종료 요청 기록을 보고
 * 복구하는 쪽이 책임진다. 여기서 목록을 지우는 것은 "이번 실행이 끝났다" 는 뜻이지
 * "업무가 성공했다" 는 뜻이 아니다.
 */
@Slf4j
@Component
public class AiChatInFlightTurnRegistry {

    /**
     * 등록·정리·차단·대기를 하나의 잠금으로 묶는다. 신규 수락 차단(blockNewTurns)과 등록(register)이
     * 서로 다른 잠금을 쓰면 "차단 플래그를 보기 전에 등록이 시작됐지만 아직 목록에 들어가지 않은" 순간이
     * 생겨, 수락해 놓고 종료 대기에서는 빠뜨리는 턴이 나온다. 잠금 안에서 하는 일은 map 연산 한 번뿐이라
     * 요청 경로에서 의미 있는 대기를 만들지 않는다.
     */
    private final ReentrantLock stateLock = new ReentrantLock();

    /** 진행 목록이 빈 순간을 종료 대기 쪽에 알리는 신호. */
    private final Condition allTurnsFinished = stateLock.newCondition();

    /** 등록 순서를 유지한다 — 종료 기한을 넘겼을 때 오래 매달린 턴부터 로그에 남기기 위해서다. */
    private final Map<Long, InFlightTurn> inFlightTurns = new LinkedHashMap<>();

    private long turnIdSequence = 0L;

    private boolean acceptingNewTurns = true;

    /**
     * 진행 중인 턴 하나의 운영 확인 정보.
     *
     * @param turnId    이 프로세스 안에서만 유효한 일련번호 — 목록에서 턴을 지목하는 열쇠다.
     * @param requestId 클라이언트가 보낸 요청 식별자(멱등성 판정의 열쇠, Task 2). 로그에서 DB 요청 기록과 맞춰 보기 위해 함께 들고 있는다.
     * @param sessionId 채팅 세션 id
     * @param userId    요청한 사용자 id
     * @param startedAt 등록 시각 — 종료 기한을 넘긴 턴이 얼마나 오래 매달렸는지 보기 위한 값이다.
     */
    public record InFlightTurn(
            long turnId,
            String requestId,
            Long sessionId,
            Long userId,
            Instant startedAt
    ) {
    }

    /**
     * 턴 하나를 진행 목록에 올린다. 선행 처리의 첫 부수 효과보다 앞서 호출해야
     * 예약·외부 호출을 시작해 놓고 추적에서 빠지는 턴이 없다.
     *
     * @throws ServiceUnavailableException 이미 종료 절차가 시작돼 신규 수락이 차단된 경우.
     *                                     조용히 통과시키지 않는다 — 받아 놓고 곧바로 잘리는 것보다 503 으로 거절하는 편이 낫다.
     */
    public InFlightTurn register(String requestId, Long sessionId, Long userId) {
        stateLock.lock();
        try {
            if (!acceptingNewTurns) {
                throw new ServiceUnavailableException(AiChatErrorCode.SERVER_SHUTTING_DOWN);
            }
            InFlightTurn turn = new InFlightTurn(++turnIdSequence, requestId, sessionId, userId, Instant.now());
            inFlightTurns.put(turn.turnId(), turn);
            return turn;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 이번 실행이 끝난 턴을 목록에서 지운다. 성공·실패·보상 실패 등 <b>모든 종료 경로</b>에서 불러야 한다.
     * 같은 턴에 두 번 이상 불러도 안전하다 — 두 번째부터는 아무것도 하지 않고 false 를 돌려준다.
     *
     * @return 이번 호출이 실제로 목록에서 지웠으면 true
     */
    public boolean finish(InFlightTurn turn) {
        return turn != null && finish(turn.turnId());
    }

    /** {@link #finish(InFlightTurn)} 와 같다. 턴 객체를 들고 다니기 어려운 호출부를 위해 열어 둔다. */
    public boolean finish(long turnId) {
        stateLock.lock();
        try {
            InFlightTurn removed = inFlightTurns.remove(turnId);
            if (removed == null) {
                return false;
            }
            if (inFlightTurns.isEmpty()) {
                allTurnsFinished.signalAll();
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 신규 턴 수락을 막는다. 이 호출이 돌아온 뒤에 시작된 등록은 모두 거절되고,
     * 이 호출 전에 수락된 턴은 전부 진행 목록에 들어가 있다(둘 사이에 끼는 턴이 없다).
     * 여러 번 불러도 안전하다.
     *
     * @return 차단 시점에 아직 진행 중이던 턴 수
     */
    public int blockNewTurns() {
        stateLock.lock();
        try {
            acceptingNewTurns = false;
            return inFlightTurns.size();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 진행 목록이 빌 때까지, 늦어도 {@code limit} 까지 기다린다.
     * 목록이 먼저 비면 기한을 다 쓰지 않고 곧바로 돌아온다.
     *
     * @return 기한 안에 목록이 비었으면 true, 기한을 넘겨 남은 턴이 있으면 false
     */
    public boolean awaitAllTurnsFinished(Duration limit) throws InterruptedException {
        long remainingNanos = limit.toNanos();
        stateLock.lock();
        try {
            while (!inFlightTurns.isEmpty()) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                remainingNanos = allTurnsFinished.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /** 현재 진행 중인 턴 목록의 사본(등록 순서). 종료 기한을 넘겼을 때 남은 턴을 로그로 남기거나 운영 확인에 쓴다. */
    public List<InFlightTurn> snapshot() {
        stateLock.lock();
        try {
            return List.copyOf(new ArrayList<>(inFlightTurns.values()));
        } finally {
            stateLock.unlock();
        }
    }

    /** 현재 진행 중인 턴 수. */
    public int inFlightCount() {
        stateLock.lock();
        try {
            return inFlightTurns.size();
        } finally {
            stateLock.unlock();
        }
    }

    /** 아직 신규 턴을 받고 있으면 true. 종료 절차가 시작되면 false 로 바뀌고 다시 true 가 되지 않는다. */
    public boolean isAcceptingNewTurns() {
        stateLock.lock();
        try {
            return acceptingNewTurns;
        } finally {
            stateLock.unlock();
        }
    }
}
