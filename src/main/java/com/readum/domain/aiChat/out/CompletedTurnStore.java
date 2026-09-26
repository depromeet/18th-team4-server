package com.readum.domain.aiChat.out;

import java.time.LocalDateTime;

/**
 * 끝까지 만들어진 답변 하나를 DB 에 확정하기 <b>전에</b> 한 번 적어 두는 보관소.
 * 프로세스가 죽거나 DB 가 잠시 응답하지 않아 확정하지 못한 답변을 나중에 되살리기 위한 근거다.
 *
 * <p>계약
 * <ul>
 *   <li><b>완성된 결과만 적는다.</b> 생성 중에는 아무것도 적지 않는다 — 조각도, 시작 표식도 없다.
 *       그래서 보관소에 기록이 있다는 것은 곧 "검증까지 끝난 답변이 통째로 있다" 는 뜻이고,
 *       읽는 쪽이 반쯤 쓰인 기록을 완성본으로 오해할 여지가 없다.</li>
 *   <li><b>한 요청의 기록은 한 번에, 한 덩어리로 적힌다.</b> 값 하나를 보관 기한과 함께 원자로 넣으므로
 *       절반만 보이는 중간 상태가 없다. 읽는 쪽이 기록을 얼려 두는 절차(봉인)가 필요 없는 이유다.</li>
 *   <li>여기서는 던지지 않는다. 보관소 고장은 거짓 또는 {@link Lookup.Unknown} 으로 알리고,
 *       대화와 정상 경로의 저장은 그대로 이어진다.</li>
 *   <li>열쇠는 요청 기록의 DB id 다 — 생성보다 먼저 만들어져 있고 재전송 멱등의 기준이기도 하다.</li>
 * </ul>
 */
public interface CompletedTurnStore {

    /**
     * 끝까지 만들어진 답변을 적는다 — <b>정상 생성 판정 직후, DB 확정 트랜잭션보다 먼저</b> 부른다.
     * 이 호출이 성공해야만 뒤이어 프로세스가 죽어도 결과를 되살릴 수 있다.
     *
     * <p>같은 요청 id 로 두 번 적히는 일은 없다 — 요청 id 는 요청 자리를 잡을 때 한 번만 만들어지고
     * 그 요청의 생성도 한 번뿐이다. 그래도 어떤 이유로 다시 불리면 <b>먼저 적힌 기록을 덮지 않는다</b>.
     *
     * @return 거짓이면 이 턴은 되살리기 보장 없이 진행한다(정상 경로의 저장·정산은 그대로 진행된다).
     */
    boolean save(long turnRequestId, CompletedTurn completedTurn);

    /**
     * 되살리기 직전에 기록을 읽는다. 여러 번 불러도 <b>같은 답</b>을 준다 — 읽기가 기록을 바꾸지 않으므로,
     * DB 확정이 실패해 다음 회차가 다시 와도 성공 결과를 잃지 않는다.
     */
    Lookup find(long turnRequestId);

    /** 기록을 지운다 — DB 에서 이 요청이 종료됐음이 <b>확인된 뒤에만</b> 부른다. */
    void delete(long turnRequestId);

    /**
     * 되살리기에 필요한 한 턴의 전부. 정상 경로가 DB 에 확정할 값과 같은 값들이라,
     * 되살려 확정한 결과는 제때 확정한 결과와 구분되지 않는다.
     *
     * @param turnRequestId        이 기록이 어느 요청의 것인지 — 열쇠와 같은 값이어야 한다(요청 신원 대조)
     * @param estimatedInputTokens 청구량의 입력 몫 — 예약 때 센 값 그대로여야 정상 경로와 청구가 같아진다
     * @param generatedAt          생성을 시작한 시각. 되살려 저장할 때도 이 시각을 답변 작성 시각으로 쓴다
     * @param content              정본 본문 전체
     * @param outputTokens         공급자 실측 출력 토큰. 추정치로 대신하지 않는다
     */
    record CompletedTurn(
            long turnRequestId,
            Long sessionId,
            Long userId,
            int estimatedInputTokens,
            LocalDateTime generatedAt,
            String content,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {
    }

    /** 읽기 결과. */
    sealed interface Lookup {

        /** 끝까지 만들어진 결과가 남아 있다 — 이대로 DB 에 확정하면 된다. */
        record Found(CompletedTurn completedTurn) implements Lookup {
        }

        /**
         * 기록이 없다 — 답변이 끝까지 가지 못했거나, 애초에 적지 못했거나, 이미 지워졌다.
         * 어느 쪽이든 되살릴 것이 없으므로 기존의 만료·예약 반환 정책으로 정리한다.
         */
        record Absent() implements Lookup {
        }

        /**
         * 알 수 없다 — 보관소가 대답하지 않았거나 기록을 해석할 수 없다. <b>없는 것과 다르다.</b>
         * 이때 예약을 돌려주면 실제로 남아 있던 성공 결과를 보지도 않고 버리게 되므로,
         * 받은 쪽은 아무것도 하지 않고 다음 회차로 미룬다.
         */
        record Unknown() implements Lookup {
        }
    }
}
