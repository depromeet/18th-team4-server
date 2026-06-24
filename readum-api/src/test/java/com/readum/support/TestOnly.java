package com.readum.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 테스트에서만 사용하는 코드임을 나타내는 표식.
 *
 * <p>이 어노테이션이 붙은 클래스/메서드는 테스트 코드에서만 호출해야 한다. 실제 서비스 코드
 * ({@code src/main}) 는 {@code src/test} 를 볼 수 없으므로 이 표식이 컴파일을 막지는 못한다.
 * 클래스 이름({@code *Fixture}) 과 {@code src/test} 위치가 1차 신호이고, 이 어노테이션은
 * 팀 컨벤션을 코드로 드러내는 의도 표식이다.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface TestOnly {
}
