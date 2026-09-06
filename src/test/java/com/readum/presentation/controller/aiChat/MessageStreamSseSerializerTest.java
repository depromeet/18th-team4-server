package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.MessageStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStreamSseSerializerTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 6, 12, 0, 0);

    private final MessageStreamSseSerializer serializer = new MessageStreamSseSerializer(new ObjectMapper());

    @Test
    void 본문_조각은_token_이벤트로_직렬화된다() {
        String wire = serialize(new MessageStreamEvent.Token("안녕"));

        assertThat(wire).contains("event:token");
        assertThat(wire).contains("{\"delta\":\"안녕\"}");
    }

    @Test
    void 델타를_끝까지_보낸_종료는_본문_없이_done_이벤트로_직렬화된다() {
        String wire = serialize(new MessageStreamEvent.Done(
                new MessageStreamEvent.TokenCount(10, 5, 15), CREATED_AT));

        assertThat(wire).contains("event:done");
        assertThat(wire).contains("\"tokenCount\":{\"input\":10,\"output\":5,\"total\":15}");
        assertThat(wire).contains("\"createdAt\"");
        assertThat(wire).doesNotContain("content");
    }

    @Test
    void 포화_후_전체_교체는_최종_본문을_실은_replace_이벤트로_직렬화된다() {
        String wire = serialize(new MessageStreamEvent.Replace(
                "완성된 전체 답변", new MessageStreamEvent.TokenCount(10, 5, 15), CREATED_AT));

        assertThat(wire).contains("event:replace");
        assertThat(wire).contains("\"content\":\"완성된 전체 답변\"");
        assertThat(wire).contains("\"tokenCount\":{\"input\":10,\"output\":5,\"total\":15}");
        assertThat(wire).contains("\"createdAt\"");
    }

    @Test
    void 실패는_code_와_message_를_실은_error_이벤트로_직렬화된다() {
        String wire = serialize(MessageStreamEvent.Error.of("AI_CHAT_GENERATION_FAILED", "생성에 실패했습니다."));

        assertThat(wire).contains("event:error");
        assertThat(wire).contains("\"code\":\"AI_CHAT_GENERATION_FAILED\"");
        assertThat(wire).contains("\"message\":\"생성에 실패했습니다.\"");
        // rateLimit 정보가 없으면 키 자체를 내보내지 않는다.
        assertThat(wire).doesNotContain("rateLimit");
    }

    /** SseEventBuilder 가 만든 조각들을 실제 전송되는 순서대로 이어 붙인다. */
    private String serialize(MessageStreamEvent event) {
        SseEmitter.SseEventBuilder builder = serializer.toSseEvent(event);
        Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
        return parts.stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
    }
}
