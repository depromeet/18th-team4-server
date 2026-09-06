package com.readum.presentation.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증 필터가 해석해 둔 현재 사용자 userId(Long) 를 주입받는다.
 * 컨트롤러가 신원을 받는 유일한 통로 — 쿠키/헤더를 직접 읽지 않는다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUserId {
}
