package com.readum.domain.summary.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claim/reaper 트랜잭션의 격리수준 계약 테스트 (이슈 #92).
 *
 * <p>워커 폴링 쿼리(findClaimable/findOrphaned)는 PENDING/PROCESSING 구간을 범위 스캔하므로
 * MySQL 기본 격리수준 REPEATABLE READ 에서 gap lock 을 건다. 이 gap lock 이 같은 구간으로 들어오는
 * 신규 PENDING INSERT 와 충돌해 락 대기 타임아웃을 냈다(#92). 처방은 이 두 트랜잭션만 READ COMMITTED 로
 * 내려 gap lock 을 없애는 것 — 어노테이션이 유실되면 사고가 재발하므로 그 부착을 못 박는다.
 *
 * <p>실제 격리수준 적용은 Spring + Hibernate 가 보장하는 동작이고, 테스트 하네스(H2)의 기본 격리수준이
 * 이미 READ COMMITTED 라 라이브로 RR↔RC 차이를 재현할 수 없다(H2 에선 어노테이션 유무가 구분되지 않음).
 * 따라서 회귀 가드는 "두 메서드에 isolation = READ_COMMITTED 가 붙어 있고 나머지엔 없음" 을 단언한다.
 * 실효(gap lock 제거)는 dev RDS MySQL 2세션 재현으로 별도 확인한다.
 */
class SummaryJobLifecycleServiceIsolationTest {

    @Test
    void claimOne_은_READ_COMMITTED_격리수준으로_선언된다() {
        assertThat(isolationOf("claimOne", String.class)).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void reclaimOrphans_는_READ_COMMITTED_격리수준으로_선언된다() {
        assertThat(isolationOf("reclaimOrphans", int.class)).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void 격리수준을_지정하지_않은_트랜잭션은_DB_기본값을_유지한다() {
        assertThat(isolationOf("prepareGeneration", Long.class, String.class))
                .isEqualTo(Isolation.DEFAULT);
    }

    private Isolation isolationOf(String methodName, Class<?>... parameterTypes) {
        Method method = findMethod(methodName, parameterTypes);
        Transactional transactional =
                AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(transactional)
                .as("%s 에 @Transactional 이 선언되어 있어야 한다", methodName)
                .isNotNull();
        return transactional.isolation();
    }

    private Method findMethod(String methodName, Class<?>... parameterTypes) {
        try {
            return SummaryJobLifecycleService.class.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("메서드를 찾을 수 없다: " + methodName, e);
        }
    }
}
