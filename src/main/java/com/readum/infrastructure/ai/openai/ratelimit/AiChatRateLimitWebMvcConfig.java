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
        registry.addInterceptor(aiChatRateLimitInterceptor)
                .addPathPatterns("/api/v1/ai-chat/**");
    }
}
