package com.readum.presentation.common.security;

import com.readum.domain.auth.service.SessionAuthenticationService;
import com.readum.domain.auth.service.TokenAuthenticationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationService tokenAuthenticationService,
            SessionAuthenticationService sessionAuthenticationService,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        // 쿠키 발급(가입)은 익명 접근이 필요한 유일한 사용자 엔드포인트
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/sessions").permitAll()
                        // 도서 검색은 쿠키를 읽지 않는 공개 API
                        .requestMatchers("/api/v1/books/**").permitAll()
                        // 운영 모니터링: Prometheus 스크래핑·헬스체크용 actuator 엔드포인트만 허용.
                        // (/actuator/metrics 는 인증을 유지한다.) 외부 노출 차단은 네트워크 레벨에서 책임진다.
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).permitAll()
                        // 신원 해석은 인증 필터(JWT/세션 쿠키)가, 인증 강제는 여기가 담당한다.
                        // 미인증 요청은 JwtAuthenticationEntryPoint 가 401 로 응답한다.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokenAuthenticationService),
                        UsernamePasswordAuthenticationFilter.class
                )
                // JWT 인증이 채우지 못한 요청에 한해 user_session 쿠키로 principal 을 채운다.
                .addFilterAfter(
                        new SessionCookieAuthenticationFilter(sessionAuthenticationService),
                        JwtAuthenticationFilter.class
                )
                .build();
    }
}
