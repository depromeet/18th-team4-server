package com.readum.infrastructure.ai.openai.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;

/**
 * OpenAI 프로젝트별 설정 — 프로젝트 하나 = API 키 하나 + 모델별 분당 한도. 외부 API 설정이라 infrastructure 에 둔다.
 *
 * <p>한도 값은 그 프로젝트에 실제로 걸린 OpenAI 한도(또는 그 안전 비율)를 그대로 적는다. 우리가 기능 간 몫을
 * 나누는 값이 아니다 — 격리는 프로젝트 분리가 하고, 여기 적힌 값은 전역 게이트 토큰 버킷의 보충 속도가 된다.
 * {@link OpenAiProject} 다섯 개 모두 설정돼 있어야 기동한다. 게이트를 거치는 프로젝트({@link OpenAiProject#gated()}
 * 가 {@code true}) 는 모델 한도가 하나 이상 있어야 하고, 거치지 않는 프로젝트(지금은 moderation)만 키만 적어도 된다.
 * 게이트를 거치는데 한도가 비어 있으면 그 프로젝트의 호출이 검사 없이 통과하므로(fail-open), 기동에서 막는다.
 */
@Validated
@ConfigurationProperties(prefix = "openai")
public record OpenAiProjectProperties(
        @NotEmpty Map<OpenAiProject, @Valid Project> projects,
        @NotNull @Valid Gate gate
) {

    public OpenAiProjectProperties {
        EnumSet<OpenAiProject> missing = EnumSet.allOf(OpenAiProject.class);
        if (projects != null) {
            missing.removeAll(projects.keySet());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("openai.projects 에 설정되지 않은 프로젝트가 있습니다: " + missing);
        }

        EnumSet<OpenAiProject> withoutModelLimit = EnumSet.noneOf(OpenAiProject.class);
        for (Map.Entry<OpenAiProject, Project> entry : projects.entrySet()) {
            Project project = entry.getValue();
            if (entry.getKey().gated() && (project == null || project.models().isEmpty())) {
                withoutModelLimit.add(entry.getKey());
            }
        }
        if (!withoutModelLimit.isEmpty()) {
            throw new IllegalStateException(
                    "전역 게이트를 거치는 프로젝트에 모델 한도(models)가 없습니다: " + withoutModelLimit);
        }
    }

    /**
     * @param apiKey 이 프로젝트의 OpenAI API 키.
     * @param models 모델별 분당 한도. {@link OpenAiProject#gated()} 가 {@code false} 인 프로젝트만 비어 있어도 된다
     *               (yml 의 {@code models: {}} 는 값 없음으로 들어와 {@code null} 이 되므로 빈 지도로 바꾼다).
     *               게이트를 거치는 프로젝트가 비어 있으면 바깥 생성자가 기동을 막는다.
     */
    public record Project(
            @NotBlank String apiKey,
            Map<String, @Valid ModelLimit> models
    ) {

        public Project {
            models = (models == null) ? Map.of() : models;
        }
    }

    public record ModelLimit(
            @Positive int requestsPerMinute,
            @Positive long tokensPerMinute
    ) {
    }

    /**
     * @param burstSeconds       토큰 버킷 크기를 "몇 초치 보충량"으로 정한다. 조용한 뒤 한 번에 내보낼 수 있는 양.
     *                           OpenAI 의 버킷 크기는 비공개라 그보다 작게 잡는다.
     * @param chatMaxWaitMillis  채팅이 버킷 자리를 기다려 줄 최대 시간. 이 안에 자리가 나면 기다렸다 진행하고,
     *                           넘으면 429 로 거절한다. 선행 처리 여유 안에 들어야 한다(AiChatTimeBudgetValidator).
     * @param quotaCooldownSeconds 계정 quota 소진 감지 시 전 경로를 막을 기본 시간 (Retry-After 없을 때).
     */
    public record Gate(
            @Positive int burstSeconds,
            @PositiveOrZero long chatMaxWaitMillis,
            @Positive int quotaCooldownSeconds
    ) {

        public Duration chatMaxWait() {
            return Duration.ofMillis(chatMaxWaitMillis);
        }
    }

    public String apiKeyOf(OpenAiProject project) {
        return projects.get(project).apiKey();
    }

    /** 해당 프로젝트에 이 모델의 한도가 없으면 {@code null}. 프로젝트 자체는 생성자가 다섯 개 모두 있음을 보장한다. */
    public ModelLimit limitOf(OpenAiProject project, String model) {
        return projects.get(project).models().get(model);
    }
}
