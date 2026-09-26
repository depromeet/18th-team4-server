package com.readum.infrastructure.ai.openai.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

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

    @Test
    void 설정되지_않은_프로젝트가_있으면_기동을_막는다() {
        Map<OpenAiProject, OpenAiProjectProperties.Project> onlyChat =
                Map.of(OpenAiProject.CHAT, new OpenAiProjectProperties.Project("test-key"));

        assertThatThrownBy(() -> new OpenAiProjectProperties(onlyChat))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("설정되지 않은 프로젝트")
                .hasMessageContaining("MODERATION")
                .hasMessageContaining("SUMMARY")
                .hasMessageContaining("CONTEXT_SUMMARY")
                .hasMessageContaining("TITLE");
    }

    @Test
    void 다섯_프로젝트의_키가_모두_있으면_생성된다() {
        assertThatCode(() -> new OpenAiProjectProperties(allProjects())).doesNotThrowAnyException();

        OpenAiProjectProperties properties = new OpenAiProjectProperties(allProjects());
        assertThat(properties.apiKeyOf(OpenAiProject.MODERATION)).isEqualTo("test-key");
    }

    @Test
    void 기능마다_프로젝트와_보호_단위가_1대1_로_맞는다() {
        // 어긋나면 호출은 A 프로젝트로 나가고 장애 상태는 B 기능에 쌓인다.
        for (OpenAiProject project : OpenAiProject.values()) {
            assertThat(OpenAiProject.of(project.capability())).isEqualTo(project);
        }
    }

    @Test
    void 우리가_추정으로_미리_거르던_전역_게이트는_남아_있지_않다() {
        // 추정 한도로 미리 거르는 방식은 걷어냈다. 되살아나면 "실제 오류로 판단한다" 는 지금 설계와 두 겹이 되고,
        // 어느 쪽이 막았는지 알 수 없는 상태가 다시 생긴다. 흔적이 남지 않았는지 여기서 못 박는다.
        assertThat(new ClassPathResource("redis/openai-token-bucket.lua").exists())
                .as("토큰 버킷 스크립트")
                .isFalse();
        assertThatThrownBy(() -> Class.forName(
                "com.readum.infrastructure.ai.openai.ratelimit.OpenAiRequestGate"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.readum.infrastructure.ai.openai.ratelimit.OpenAiRateLimitGuard"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.readum.domain.summary.out.AiQuotaCooldown"))
                .as("게이트 쿨다운을 도메인에 노출하던 포트")
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static Map<OpenAiProject, OpenAiProjectProperties.Project> allProjects() {
        Map<OpenAiProject, OpenAiProjectProperties.Project> projects = new HashMap<>();
        for (OpenAiProject project : OpenAiProject.values()) {
            projects.put(project, new OpenAiProjectProperties.Project("test-key"));
        }
        return projects;
    }
}
