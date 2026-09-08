package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정이 어긋나면 기동을 막는지 보는 단위 테스트. 값 검사가 아니라 <b>기동 차단</b>이 이 record 의 일이라,
 * 스프링 컨텍스트 없이 record 를 직접 만들어 생성자가 던지는지만 본다.
 */
class OpenAiProjectPropertiesTest {

    private static final String MODEL = "gpt-4o-mini";

    private static final Map<String, OpenAiProjectProperties.ModelLimit> LIMITS =
            Map.of(MODEL, new OpenAiProjectProperties.ModelLimit(9000, 180000L));

    private static final OpenAiProjectProperties.Gate GATE =
            new OpenAiProjectProperties.Gate(10, 1000, 300);

    @Test
    void 설정되지_않은_프로젝트가_있으면_기동을_막는다() {
        Map<OpenAiProject, OpenAiProjectProperties.Project> onlyChat =
                Map.of(OpenAiProject.CHAT, project(LIMITS));

        assertThatThrownBy(() -> new OpenAiProjectProperties(onlyChat, GATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("설정되지 않은 프로젝트")
                .hasMessageContaining("MODERATION")
                .hasMessageContaining("SUMMARY")
                .hasMessageContaining("CONTEXT_SUMMARY")
                .hasMessageContaining("TITLE");
    }

    @Test
    void 게이트를_거치는_프로젝트에_모델_한도가_없으면_기동을_막는다() {
        // 한도가 비면 그 프로젝트의 호출은 게이트를 검사 없이 통과한다 — 조용히 새지 않게 기동에서 막는다.
        Map<OpenAiProject, OpenAiProjectProperties.Project> projects = allProjects();
        projects.put(OpenAiProject.TITLE, project(null));

        assertThatThrownBy(() -> new OpenAiProjectProperties(projects, GATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("모델 한도(models)")
                .hasMessageContaining("TITLE");
    }

    @Test
    void moderation_은_모델_한도_없이_키만_있어도_생성된다() {
        Map<OpenAiProject, OpenAiProjectProperties.Project> projects = allProjects();
        projects.put(OpenAiProject.MODERATION, project(null));

        assertThatCode(() -> new OpenAiProjectProperties(projects, GATE)).doesNotThrowAnyException();

        OpenAiProjectProperties properties = new OpenAiProjectProperties(projects, GATE);
        assertThat(properties.limitOf(OpenAiProject.MODERATION, "x")).isNull();
    }

    /** 다섯 프로젝트 모두 한도를 갖춘 정상 설정 — 각 테스트가 한 항목만 어긋나게 바꿔 쓴다. */
    private static Map<OpenAiProject, OpenAiProjectProperties.Project> allProjects() {
        Map<OpenAiProject, OpenAiProjectProperties.Project> projects = new HashMap<>();
        for (OpenAiProject project : OpenAiProject.values()) {
            projects.put(project, project(LIMITS));
        }
        return projects;
    }

    private static OpenAiProjectProperties.Project project(
            Map<String, OpenAiProjectProperties.ModelLimit> models) {
        return new OpenAiProjectProperties.Project("test-key", models);
    }
}
