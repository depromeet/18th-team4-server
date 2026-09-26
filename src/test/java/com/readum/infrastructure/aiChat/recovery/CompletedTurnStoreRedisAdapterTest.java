package com.readum.infrastructure.aiChat.recovery;

import com.readum.domain.aiChat.config.CompletedTurnStoreProperties;
import com.readum.domain.aiChat.out.CompletedTurnStore;
import com.readum.support.EmbeddedRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완성 답변 보관소를 <b>실제 Redis</b> 위에서 검증한다. 흉내 낸 구현으로는 볼 수 없는 것들이 대상이다 —
 * 값과 보관 기한이 한 명령으로 함께 정해지는지, 먼저 적힌 기록을 덮지 않는지,
 * 읽기가 기록을 바꾸지 않아 몇 번을 읽어도 같은 답이 나오는지.
 */
class CompletedTurnStoreRedisAdapterTest {

    private static final long TURN_REQUEST_ID = 4242L;
    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 9, 12, 10, 0, 0);
    private static final long RETENTION_HOURS = 24;

    private static EmbeddedRedisServer redis;

    private CompletedTurnStoreRedisAdapter store;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = EmbeddedRedisServer.startOrSkip();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void freshStore() {
        redis.deleteKeys("ai:chat:completion:*");
        store = adapterOn(redis.template());
    }

    private static CompletedTurnStoreRedisAdapter adapterOn(StringRedisTemplate template) {
        return new CompletedTurnStoreRedisAdapter(
                template, JsonMapper.builder().build(), new CompletedTurnStoreProperties(RETENTION_HOURS));
    }

    // --- 정상 흐름 -------------------------------------------------------------------------------

    @Test
    void 적어_둔_완성본을_그대로_되살릴_수_있다() {
        store.save(TURN_REQUEST_ID, completedTurn("안녕하세요"));

        CompletedTurnStore.Lookup lookup = store.find(TURN_REQUEST_ID);

        assertThat(lookup).isInstanceOf(CompletedTurnStore.Lookup.Found.class);
        CompletedTurnStore.CompletedTurn found = ((CompletedTurnStore.Lookup.Found) lookup).completedTurn();
        assertThat(found.content()).isEqualTo("안녕하세요");
        assertThat(found.finishReason()).isEqualTo("STOP");
        assertThat(found.inputTokens()).isEqualTo(100);
        assertThat(found.outputTokens()).isEqualTo(7);
        assertThat(found.totalTokens()).isEqualTo(107);
        assertThat(found.sessionId()).isEqualTo(7L);
        assertThat(found.userId()).isEqualTo(3L);
        assertThat(found.estimatedInputTokens()).isEqualTo(11);
        assertThat(found.generatedAt())
                .as("되살려 저장할 때 쓸 작성 시각이 그대로 살아 있어야 한다")
                .isEqualTo(GENERATED_AT);
        assertThat(found.turnRequestId()).isEqualTo(TURN_REQUEST_ID);
    }

    @Test
    void 값과_보관_기한이_한_번에_정해진다() {
        // 값만 먼저 들어가고 기한이 나중에 붙으면, 그 사이에 프로세스가 죽었을 때 영영 남는 기록이 생긴다.
        store.save(TURN_REQUEST_ID, completedTurn("답변"));

        Long ttlSeconds = redis.template().getExpire(key(), TimeUnit.SECONDS);

        assertThat(ttlSeconds).isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(RETENTION_HOURS * 3600);
    }

    @Test
    void 여러_번_읽어도_같은_답을_준다() {
        // DB 확정이 실패해 다음 회차가 다시 와도 성공 결과를 잃지 않아야 한다.
        store.save(TURN_REQUEST_ID, completedTurn("답변"));

        CompletedTurnStore.Lookup first = store.find(TURN_REQUEST_ID);
        CompletedTurnStore.Lookup second = store.find(TURN_REQUEST_ID);

        assertThat(first).isInstanceOf(CompletedTurnStore.Lookup.Found.class);
        assertThat(second)
                .as("두 번째 회차가 없음으로 읽으면 멀쩡한 답변이 환불로 사라진다")
                .isInstanceOf(CompletedTurnStore.Lookup.Found.class);
    }

    @Test
    void 읽기는_보관_기한을_늘리지_않는다() {
        // 회수 스캔은 주기마다 같은 행을 다시 읽는다. 그때마다 기한이 늘면 되살릴 수 없는 기록이 영영 남아,
        // 문서에 적은 보관 상한이 상한이 아니게 된다.
        store.save(TURN_REQUEST_ID, completedTurn("답변"));
        Long beforeRead = redis.template().getExpire(key(), TimeUnit.MILLISECONDS);

        store.find(TURN_REQUEST_ID);
        store.find(TURN_REQUEST_ID);

        assertThat(redis.template().getExpire(key(), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(beforeRead);
    }

    @Test
    void 먼저_적힌_기록을_덮지_않는다() {
        // 같은 요청 id 의 기록은 같은 생성 결과뿐이다. 그래도 덮어쓰기를 열어 두면
        // 뒤늦은 호출이 되살릴 근거를 바꿔 놓을 수 있다.
        store.save(TURN_REQUEST_ID, completedTurn("먼저 적힌 답변"));

        boolean secondSave = store.save(TURN_REQUEST_ID, completedTurn("나중에 온 답변"));

        assertThat(secondSave)
                .as("이미 있다는 답도 '되살릴 근거가 남았다' 라는 뜻이라 성공이다")
                .isTrue();
        CompletedTurnStore.Lookup lookup = store.find(TURN_REQUEST_ID);
        assertThat(((CompletedTurnStore.Lookup.Found) lookup).completedTurn().content())
                .isEqualTo("먼저 적힌 답변");
    }

    @Test
    void 기록이_아예_없으면_없다고_답한다() {
        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Absent.class);
    }

    @Test
    void 지우고_나면_없는_것으로_읽힌다() {
        store.save(TURN_REQUEST_ID, completedTurn("답변"));

        store.delete(TURN_REQUEST_ID);

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Absent.class);
    }

    @Test
    void 한_턴의_기록은_다른_턴의_기록과_섞이지_않는다() {
        store.save(TURN_REQUEST_ID, completedTurn("이 턴의 답변"));

        assertThat(store.find(TURN_REQUEST_ID + 1)).isInstanceOf(CompletedTurnStore.Lookup.Absent.class);
    }

    // --- 믿을 수 없는 기록은 없는 것이 아니다 -----------------------------------------------------------

    @Test
    void 해석할_수_없는_기록은_없음이_아니라_알_수_없음으로_답한다() {
        redis.template().opsForValue().set(key(), "{이건 JSON 이 아니다");

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 다른_요청의_기록이_그_자리에_있으면_되살리지_않고_판단을_미룬다() {
        // 열쇠와 기록 안의 요청 신원이 다르다 — 남의 답변을 이 요청의 성공으로 만들지 않는다.
        store.save(TURN_REQUEST_ID, completedTurn("답변"));
        String other = redis.template().opsForValue().get(key());
        redis.template().opsForValue().set("ai:chat:completion:" + (TURN_REQUEST_ID + 1), other);

        assertThat(store.find(TURN_REQUEST_ID + 1)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 정상_종료_사유가_아니면_되살리지_않고_판단을_미룬다() {
        // 답변이 잘린 LENGTH 는 정상 경로에서도 성공이 아니다. 되살리기가 그 판정을 느슨하게 만들면 안 된다.
        store.save(TURN_REQUEST_ID, new CompletedTurnStore.CompletedTurn(
                TURN_REQUEST_ID, 7L, 3L, 11, GENERATED_AT, "잘린 답변", "LENGTH", 100, 7, 107));

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 실측_사용량이_온전하지_않으면_되살리지_않고_판단을_미룬다() {
        // 정상 경로의 성공 판정과 같은 기준을 다시 본다 — 기록이 있다는 것만 믿고 되살리면
        // 검증하지 않은 성공을 만들어 사용자에게 청구하게 된다.
        store.save(TURN_REQUEST_ID, new CompletedTurnStore.CompletedTurn(
                TURN_REQUEST_ID, 7L, 3L, 11, GENERATED_AT, "답변", "STOP", 100, null, 107));

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 값이_JSON_리터럴_null_이면_없음이_아니라_알_수_없음으로_답한다() {
        redis.template().opsForValue().set(key(), "null");

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 입력_추정_토큰이_음수면_되살리지_않고_판단을_미룬다() {
        // 음수를 그대로 쓰면 정산이 예약을 실제보다 많이 돌려준다.
        store.save(TURN_REQUEST_ID, new CompletedTurnStore.CompletedTurn(
                TURN_REQUEST_ID, 7L, 3L, -11, GENERATED_AT, "답변", "STOP", 100, 7, 107));

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void 본문이_비어_있으면_되살리지_않고_판단을_미룬다() {
        store.save(TURN_REQUEST_ID, new CompletedTurnStore.CompletedTurn(
                TURN_REQUEST_ID, 7L, 3L, 11, GENERATED_AT, "", "STOP", 100, 7, 107));

        assertThat(store.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    // --- 읽지 못한 것은 없는 것이 아니다 ---------------------------------------------------------------

    @Test
    void Redis_가_대답하지_않으면_없음이_아니라_알_수_없음으로_답한다() {
        // 못 읽었다는 이유로 환불하면, 실제로는 남아 있던 성공 결과를 보지도 않고 버린다.
        CompletedTurnStoreRedisAdapter failing = adapterOn(new FailingRedisTemplate(redis.connectionFactory()));

        assertThat(failing.find(TURN_REQUEST_ID)).isInstanceOf(CompletedTurnStore.Lookup.Unknown.class);
    }

    @Test
    void Redis_가_대답하지_않아도_쓰기는_던지지_않는다() {
        // 보관소는 대화의 조건이 아니다 — 고장이 대화를 멈추면 안 된다.
        CompletedTurnStoreRedisAdapter failing = adapterOn(new FailingRedisTemplate(redis.connectionFactory()));

        assertThat(failing.save(TURN_REQUEST_ID, completedTurn("답변"))).isFalse();
        failing.delete(TURN_REQUEST_ID);
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    private static CompletedTurnStore.CompletedTurn completedTurn(String content) {
        return new CompletedTurnStore.CompletedTurn(
                TURN_REQUEST_ID, 7L, 3L, 11, GENERATED_AT, content, "STOP", 100, 7, 107);
    }

    private static String key() {
        return "ai:chat:completion:" + TURN_REQUEST_ID;
    }

    /** 값 연산만 실패하는 템플릿 — 보관소가 대답하지 않는 상황. */
    private static final class FailingRedisTemplate extends StringRedisTemplate {

        private FailingRedisTemplate(RedisConnectionFactory connectionFactory) {
            super(connectionFactory);
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            throw new QueryTimeoutException("시험용 Redis 장애");
        }

        @Override
        public Boolean delete(String key) {
            throw new QueryTimeoutException("시험용 Redis 장애");
        }
    }
}
