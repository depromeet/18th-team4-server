package com.readum.infrastructure.aiChat.recovery;

import com.readum.domain.aiChat.config.CompletedTurnStoreProperties;
import com.readum.domain.aiChat.out.CompletedTurnStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 완성된 답변 보관소의 Redis 구현. 한 턴의 기록은 <b>키 하나에 담긴 값 하나</b>이고,
 * 그 값은 생성이 정상으로 끝난 뒤 <b>한 번만</b> 쓰인다.
 *
 * <p><b>왜 스크립트도 봉인도 없는가.</b> 전이가 없기 때문이다. {@code SET ... NX PX} 명령 하나가
 * 값과 보관 기한을 함께 정하므로 절반만 보이는 중간 상태가 생기지 않는다. 생성 중에 아무것도 적지 않으니
 * 읽는 쪽이 "아직 자라는 중인 기록" 을 만날 일도 없고, 그래서 기록을 얼려 두는 절차가 필요 없다.
 *
 * <p>읽기의 불변식: <b>확실히 없을 때만</b> {@link CompletedTurnStore.Lookup.Absent} 다. 연결 실패·해석 불가·
 * 모양 불일치는 모두 {@link CompletedTurnStore.Lookup.Unknown} 이라 판단을 미룬다 — 못 읽은 것을 없는 것으로
 * 보면 남아 있던 성공 결과를 버린다.
 */
@Slf4j
@Component
public class CompletedTurnStoreRedisAdapter implements CompletedTurnStore {

    private static final String KEY_PREFIX = "ai:chat:completion:";

    /** 정상 종료로 인정하는 공급자 종료 사유 — 정상 경로의 판정과 같은 값이다. */
    private static final String NORMAL_FINISH_REASON = "STOP";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final CompletedTurnStoreProperties properties;

    public CompletedTurnStoreRedisAdapter(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            CompletedTurnStoreProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 완성본을 값 하나로 적는다 — 보관 기한까지 같은 명령에 실어 원자로 넣는다.
     *
     * <p>{@code NX} 로 <b>먼저 적힌 기록을 덮지 않는다</b>. 이미 있다는 답도 성공으로 본다:
     * 열쇠가 요청 기록의 DB id 라 그 자리에 있는 기록은 <b>같은 요청의 같은 생성 결과</b>뿐이고,
     * 호출자가 알아야 할 것은 "되살릴 근거가 남았는가" 하나이기 때문이다.
     */
    @Override
    public boolean save(long turnRequestId, CompletedTurn completedTurn) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(StoredTurn.from(completedTurn));
        } catch (JacksonException cannotSerialize) {
            log.error("완성 답변 기록을 만들지 못했다 — 이 턴은 되살리기 보장 없이 진행한다 turnRequestId={}",
                    turnRequestId, cannotSerialize);
            return false;
        }
        try {
            Boolean stored = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key(turnRequestId), payload, properties.retention());
            // null 은 명령의 결과를 받지 못했다는 뜻이다 — 적혔다고 단정하지 않는다.
            return stored != null;
        } catch (DataAccessException redisFailure) {
            log.warn("완성 답변 기록 실패 — 이 턴은 되살리기 보장 없이 진행한다 turnRequestId={}",
                    turnRequestId, redisFailure);
            return false;
        }
    }

    @Override
    public Lookup find(long turnRequestId) {
        String payload;
        try {
            payload = stringRedisTemplate.opsForValue().get(key(turnRequestId));
        } catch (DataAccessException redisFailure) {
            log.error("완성 답변 기록 조회 실패 — 이번 회차에서는 판단하지 않는다 turnRequestId={}",
                    turnRequestId, redisFailure);
            return new Lookup.Unknown();
        }
        if (payload == null) {
            return new Lookup.Absent();
        }
        StoredTurn storedTurn;
        try {
            storedTurn = objectMapper.readValue(payload, StoredTurn.class);
        } catch (JacksonException cannotParse) {
            return unknown(turnRequestId, "기록을 해석할 수 없다");
        }
        if (storedTurn == null) {
            // 값이 JSON 리터럴 null 이면 해석은 되지만 내용이 없다 — 없는 것과 구분해 판단을 미룬다.
            return unknown(turnRequestId, "기록에 내용이 없다");
        }
        return toLookup(turnRequestId, storedTurn);
    }

    @Override
    public void delete(long turnRequestId) {
        try {
            stringRedisTemplate.delete(key(turnRequestId));
        } catch (DataAccessException redisFailure) {
            // 지우지 못해도 해롭지 않다 — 보관 기한이 지나면 사라지고, 다시 읽어도 DB 의 종료 상태가 되살리기를 막는다.
            log.warn("완성 답변 기록 삭제 실패 — 보관 기한에 맡긴다 turnRequestId={}", turnRequestId, redisFailure);
        }
    }

    /**
     * 읽어 온 기록을 도메인 값으로 옮긴다.
     *
     * <p><b>정상 경로와 같은 성공 조건을 다시 확인한다</b> — 요청 신원이 열쇠와 같고, 종료 사유가
     * {@code STOP} 이고, 세 사용량이 모두 있고 음수가 없고 합계가 0 이 아니며, 입력 추정이 음수가 아니고,
     * 본문이 비어 있지 않아야 한다
     * ({@code AiChatGenerationAccumulator} 의 판정과 같다). 기록이 있다는 것만 믿고 되살리면,
     * 검증하지 않은 성공을 만들어 사용자에게 청구하게 된다.
     *
     * <p>조건에 못 미치면 {@link Lookup.Unknown} 이다 — <b>{@link Lookup.Absent} 가 아니다</b>.
     * 없음은 "되살릴 것이 없으니 환불" 이라는 뜻인데, 모양이 어긋난 기록은 그 증거가 아니다.
     * 읽기는 보관 기한을 다시 걸지 않으므로, 그런 기록도 적을 때 정한 기한이 지나면 없는 것으로 읽혀
     * 기존 환불 정책으로 정리된다.
     */
    private Lookup toLookup(long turnRequestId, StoredTurn storedTurn) {
        if (storedTurn.turnRequestId() != turnRequestId) {
            return unknown(turnRequestId, "기록에 적힌 요청과 찾는 요청이 다르다: " + storedTurn.turnRequestId());
        }
        LocalDateTime generatedAt = parseGeneratedAt(storedTurn.generatedAt());
        if (generatedAt == null || storedTurn.sessionId() == null || storedTurn.userId() == null
                || storedTurn.estimatedInputTokens() == null
                || storedTurn.content() == null || storedTurn.content().isEmpty()) {
            return unknown(turnRequestId, "되살리기에 필요한 값이 빠졌다");
        }
        if (storedTurn.estimatedInputTokens() < 0) {
            // 음수 입력 추정을 그대로 쓰면 정산에서 예약을 실제보다 많이 돌려주게 된다.
            return unknown(turnRequestId, "입력 추정 토큰이 음수다: " + storedTurn.estimatedInputTokens());
        }
        if (!NORMAL_FINISH_REASON.equalsIgnoreCase(storedTurn.finishReason())) {
            return unknown(turnRequestId, "정상 종료 사유가 아니다: " + storedTurn.finishReason());
        }
        if (!isValidUsage(storedTurn.inputTokens(), storedTurn.outputTokens(), storedTurn.totalTokens())) {
            return unknown(turnRequestId, "실측 사용량이 온전하지 않다");
        }
        return new Lookup.Found(new CompletedTurn(
                turnRequestId,
                storedTurn.sessionId(),
                storedTurn.userId(),
                storedTurn.estimatedInputTokens(),
                generatedAt,
                storedTurn.content(),
                storedTurn.finishReason(),
                storedTurn.inputTokens(),
                storedTurn.outputTokens(),
                storedTurn.totalTokens()));
    }

    /** 정상 경로의 {@code AiChatStreamChunk#hasValidUsage()} 와 같은 기준. */
    private static boolean isValidUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        if (inputTokens == null || outputTokens == null || totalTokens == null) {
            return false;
        }
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            return false;
        }
        return totalTokens > 0;
    }

    private Lookup unknown(long turnRequestId, String reason) {
        log.error("완성 답변 기록을 믿을 수 없다 — 이번 회차에서는 판단하지 않는다 turnRequestId={} 사유={}",
                turnRequestId, reason);
        return new Lookup.Unknown();
    }

    private static LocalDateTime parseGeneratedAt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException notATimestamp) {
            return null;
        }
    }

    private static String key(long turnRequestId) {
        return KEY_PREFIX + turnRequestId;
    }

    /**
     * Redis 에 실제로 담기는 모양. 시각을 <b>문자열로 직접</b> 적는 이유는, 이 기록의 해석이
     * 애플리케이션 전역 JSON 설정이 바뀌어도 흔들리지 않게 하기 위해서다 —
     * 보관 기한이 24시간이라 적을 때와 읽을 때의 설정이 다를 수 있다.
     *
     * <p>모든 칸을 감싸는 타입으로 둔 것도 같은 이유다. 빠진 칸이 있으면 기본값으로 메워지는 대신
     * {@code null} 로 남아 위의 검증에 걸린다.
     */
    record StoredTurn(
            long turnRequestId,
            Long sessionId,
            Long userId,
            Integer estimatedInputTokens,
            String generatedAt,
            String content,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens
    ) {

        static StoredTurn from(CompletedTurn completedTurn) {
            return new StoredTurn(
                    completedTurn.turnRequestId(),
                    completedTurn.sessionId(),
                    completedTurn.userId(),
                    completedTurn.estimatedInputTokens(),
                    completedTurn.generatedAt() == null
                            ? null
                            : completedTurn.generatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    completedTurn.content(),
                    completedTurn.finishReason(),
                    completedTurn.inputTokens(),
                    completedTurn.outputTokens(),
                    completedTurn.totalTokens());
        }
    }
}
