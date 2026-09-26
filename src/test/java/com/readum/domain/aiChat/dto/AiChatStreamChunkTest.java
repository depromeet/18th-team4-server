package com.readum.domain.aiChat.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatStreamChunkTest {

    @Test
    void 본문_조각_청크는_delta_만_담고_종료_사유와_사용량은_비어_있다() {
        AiChatStreamChunk chunk = AiChatStreamChunk.ofDelta("안녕");

        assertThat(chunk.hasDelta()).isTrue();
        assertThat(chunk.hasFinishReason()).isFalse();
        assertThat(chunk.hasValidUsage()).isFalse();
        assertThat(chunk.isMetadataOnly()).isFalse();
    }

    @Test
    void 종료_사유만_담긴_청크는_본문이_없어도_정상_메타데이터_청크로_표현된다() {
        AiChatStreamChunk chunk = AiChatStreamChunk.ofFinishReason("STOP");

        assertThat(chunk.hasDelta()).isFalse();
        assertThat(chunk.isMetadataOnly()).isTrue();
        assertThat(chunk.finishReason()).isEqualTo("STOP");
    }

    @Test
    void 사용량만_담긴_청크는_본문이_없어도_정상_메타데이터_청크로_표현된다() {
        AiChatStreamChunk chunk = AiChatStreamChunk.ofUsage(10, 5, 15);

        assertThat(chunk.hasDelta()).isFalse();
        assertThat(chunk.isMetadataOnly()).isTrue();
        assertThat(chunk.hasValidUsage()).isTrue();
    }

    @Test
    void 종료_사유는_공급자가_준_값을_그대로_보존한다() {
        assertThat(AiChatStreamChunk.ofFinishReason("LENGTH").finishReason()).isEqualTo("LENGTH");
        assertThat(new AiChatStreamChunk("조각", "STOP", 10, 5, 15).finishReason()).isEqualTo("STOP");
    }

    @Test
    void 종료_사유가_없거나_빈_문자열이면_사유가_실리지_않은_것으로_본다() {
        assertThat(AiChatStreamChunk.ofDelta("조각").hasFinishReason()).isFalse();
        assertThat(new AiChatStreamChunk("조각", "", null, null, null).hasFinishReason()).isFalse();
        assertThat(new AiChatStreamChunk("조각", "  ", null, null, null).hasFinishReason()).isFalse();
    }

    @Test
    void 세_값이_모두_있고_전체가_양수인_사용량만_실측으로_인정한다() {
        assertThat(AiChatStreamChunk.ofUsage(10, 5, 15).hasValidUsage()).isTrue();
        // 출력이 0 이어도 전체가 양수면 실측으로 인정한다 — 입력만 소모하고 끝난 응답이 있을 수 있다.
        assertThat(AiChatStreamChunk.ofUsage(10, 0, 10).hasValidUsage()).isTrue();
    }

    @Test
    void 값이_하나라도_비면_사용량을_실측으로_인정하지_않는다() {
        assertThat(AiChatStreamChunk.ofUsage(null, 5, 15).hasValidUsage()).isFalse();
        assertThat(AiChatStreamChunk.ofUsage(10, null, 15).hasValidUsage()).isFalse();
        assertThat(AiChatStreamChunk.ofUsage(10, 5, null).hasValidUsage()).isFalse();
    }

    @Test
    void 음수가_섞인_사용량은_실측으로_인정하지_않는다() {
        assertThat(AiChatStreamChunk.ofUsage(-1, 5, 15).hasValidUsage()).isFalse();
        assertThat(AiChatStreamChunk.ofUsage(10, -5, 15).hasValidUsage()).isFalse();
        assertThat(AiChatStreamChunk.ofUsage(10, 5, -15).hasValidUsage()).isFalse();
    }

    @Test
    void 전부_0_인_사용량은_중간_청크의_빈_값이므로_실측으로_인정하지_않는다() {
        assertThat(AiChatStreamChunk.ofUsage(0, 0, 0).hasValidUsage()).isFalse();
        assertThat(new AiChatStreamChunk("조각", null, 0, 0, 0).hasValidUsage()).isFalse();
    }
}
