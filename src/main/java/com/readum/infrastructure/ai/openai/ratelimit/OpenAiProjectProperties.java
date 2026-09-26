package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.EnumSet;
import java.util.Map;

/**
 * OpenAI 프로젝트별 설정 — 프로젝트 하나 = API 키 하나. 외부 API 설정이라 infrastructure 에 둔다.
 *
 * <p>여기에 분당 한도를 적지 않는다. 한도의 실제 값은 공급자만 알고, 우리가 적어 둔 추정 한도로 미리 거르면
 * 두 가지가 어긋난다 — 적어 둔 값이 실제보다 크면 걸러 주지 못하고, 작으면 멀쩡한 요청을 우리 손으로 막는다.
 * 지금은 공급자가 실제로 돌려준 429·결제 오류를 보고 그 기능을 차단한다
 * ({@code AiProviderCircuitBreaker}). 이 설정이 하는 일은 기능마다 다른 키를 쓰게 하는 것 하나다 —
 * 그래야 한 프로젝트의 한도 소진·결제 문제가 다른 기능을 함께 막지 않는다.
 *
 * <p>{@link OpenAiProject} 다섯 개 모두 설정돼 있어야 기동한다.
 */
@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProjectProperties(
        @NotEmpty Map<OpenAiProject, @Valid Project> projects
) {

    public OpenAiProjectProperties {
        EnumSet<OpenAiProject> missing = EnumSet.allOf(OpenAiProject.class);
        if (projects != null) {
            missing.removeAll(projects.keySet());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("openai.projects 에 설정되지 않은 프로젝트가 있습니다: " + missing);
        }
    }

    /**
     * @param apiKey 이 프로젝트의 OpenAI API 키.
     */
    public record Project(@NotBlank String apiKey) {
    }

    public String apiKeyOf(OpenAiProject project) {
        return projects.get(project).apiKey();
    }
}
