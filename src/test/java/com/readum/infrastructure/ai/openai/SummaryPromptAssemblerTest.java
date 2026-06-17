package com.readum.infrastructure.ai.openai;

import com.readum.model.aiChat.entity.AiChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SummaryPromptAssembler} 의 대화 이력 포맷 동작을 검증한다.
 *
 * <p>systemPrompt 파일 로딩({@link SummaryPromptAssembler#init()})과 분리해
 * 포맷 로직만 단위 테스트한다. 프롬프트 파일 자체는 AiSummaryClientImpl 의 스프링 컨텍스트
 * 통합 테스트에서 검증한다.
 */
class SummaryPromptAssemblerTest {

    private SummaryPromptAssembler assembler;

    @BeforeEach
    void setUp() {
        // init() 을 수동 호출하지 않는다 — 파일 로딩 없이 포맷 메서드만 검증하는 테스트.
        assembler = new SummaryPromptAssembler();
    }

    @Test
    void USER_메시지는_User_접두어로_포맷된다() {
        AiChatMessage userMsg = AiChatMessage.createUserMessage(1L, "이 책 정말 재밌었어요");

        String result = assembler.formatChatHistory(List.of(userMsg));

        assertThat(result).isEqualTo("User: 이 책 정말 재밌었어요");
    }

    @Test
    void ASSISTANT_메시지는_Assistant_접두어로_포맷된다() {
        AiChatMessage assistantMsg = AiChatMessage.createAssistantSuccess(
                1L, "그렇군요, 어떤 점이 인상 깊었나요?", null, null, null);

        String result = assembler.formatChatHistory(List.of(assistantMsg));

        assertThat(result).isEqualTo("Assistant: 그렇군요, 어떤 점이 인상 깊었나요?");
    }

    @Test
    void 여러_메시지는_역할_순서대로_포맷된다() {
        List<AiChatMessage> messages = List.of(
                AiChatMessage.createUserMessage(1L, "주인공이 인상 깊었어요."),
                AiChatMessage.createAssistantSuccess(1L, "어떤 부분이 특히 기억에 남나요?", null, null, null),
                AiChatMessage.createUserMessage(1L, "용기 있게 선택하는 장면이요.")
        );

        String result = assembler.formatChatHistory(messages);

        assertThat(result).isEqualTo(
                "User: 주인공이 인상 깊었어요.\n"
                + "Assistant: 어떤 부분이 특히 기억에 남나요?\n"
                + "User: 용기 있게 선택하는 장면이요."
        );
    }

    @Test
    void 빈_메시지_목록은_빈_문자열을_반환한다() {
        String result = assembler.formatChatHistory(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void buildUserMessage는_대화이력_형식으로_조립된다() {
        List<AiChatMessage> messages = List.of(
                AiChatMessage.createUserMessage(1L, "느낀 점을 말할게요.")
        );

        String result = assembler.buildUserMessage(messages);

        assertThat(result)
                .startsWith("[대화 이력]\n")
                .contains("User: 느낀 점을 말할게요.")
                .endsWith("위 대화 이력을 바탕으로 감상문 초안을 작성해 주세요.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseFormatSchema는_OpenAI_JSON스키마_구조를_반환한다() {
        Map<String, Object> schema = assembler.responseFormatSchema();

        assertThat(schema).containsKey("type");
        assertThat(schema.get("type")).isEqualTo("object");

        assertThat(schema).containsKey("properties");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("title");
        assertThat(properties).containsKey("body");

        assertThat(schema).containsKey("required");
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactlyInAnyOrder("title", "body");

        assertThat(schema).containsEntry("additionalProperties", false);
    }
}
