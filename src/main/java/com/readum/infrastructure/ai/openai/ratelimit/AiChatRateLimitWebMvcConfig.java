package com.readum.infrastructure.ai.openai.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class AiChatRateLimitWebMvcConfig implements WebMvcConfigurer {

    private final AiChatRateLimitInterceptor aiChatRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 비싼 LLM 호출 2개만 대상 — 이력 조회 GET·eligibility GET·세션 생성 POST 는 한도를 소비하지 않는다.
        registry.addInterceptor(aiChatRateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/ai-chat/sessions/*/messages",
                        "/api/v1/ai-chat/sessions/*/summary-draft"
                );
    }
}
