package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatGenerationOutcome;
import com.readum.domain.aiChat.dto.AiChatStreamChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatGenerationAccumulatorTest {

    @Test
    void 종료_사유_STOP_과_유효_사용량과_본문이_모두_갖춰지면_정상_완료로_판정한다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은 "));
        accumulator.accept(AiChatStreamChunk.ofDelta("추리 소설이다."));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        AiChatGenerationOutcome outcome = accumulator.completeNormally();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.content()).isEqualTo("이 책은 추리 소설이다.");
        assertThat(outcome.finishReason()).isEqualTo("STOP");
        assertThat(outcome.inputTokens()).isEqualTo(10);
        assertThat(outcome.outputTokens()).isEqualTo(5);
        assertThat(outcome.totalTokens()).isEqualTo(15);
    }

    @Test
    void 종료_사유를_한_번도_받지_못하면_정상_완료가_아니다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        assertThat(accumulator.completeNormally().status())
                .isEqualTo(AiChatGenerationOutcome.Status.NO_FINISH_REASON);
    }

    @Test
    void 잘린_응답의_종료_사유는_정상_완료로_인정하지_않는다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("LENGTH"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        assertThat(accumulator.completeNormally().status())
                .isEqualTo(AiChatGenerationOutcome.Status.ABNORMAL_FINISH_REASON);
    }

    @Test
    void 유효한_최종_사용량을_받지_못하면_정상_완료가_아니다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));

        assertThat(accumulator.completeNormally().status())
                .isEqualTo(AiChatGenerationOutcome.Status.NO_USAGE);
    }

    @Test
    void 중간_청크의_0_짜리_사용량은_실측으로_인정하지_않는다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(new AiChatStreamChunk("이 책은", null, 0, 0, 0));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));

        assertThat(accumulator.completeNormally().status())
                .isEqualTo(AiChatGenerationOutcome.Status.NO_USAGE);
    }

    @Test
    void 종료_사유와_사용량이_와도_본문이_비었으면_정상_완료가_아니다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        assertThat(accumulator.completeNormally().status())
                .isEqualTo(AiChatGenerationOutcome.Status.EMPTY_CONTENT);
    }

    @Test
    void 본문_없이_메타데이터만_실린_청크가_섞여도_정상_완료_판정을_방해하지_않는다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta(""));
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofDelta(""));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        assertThat(accumulator.completeNormally().isSuccess()).isTrue();
    }

    @Test
    void 사용량은_더하지_않고_마지막_유효_값을_쓴다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));
        accumulator.accept(AiChatStreamChunk.ofUsage(20, 8, 28));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));

        assertThat(accumulator.completeNormally().totalTokens()).isEqualTo(28);
    }

    @Test
    void STOP_과_사용량을_이미_받았어도_뒤이어_오류가_나면_실패로_판정한다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        AiChatGenerationOutcome outcome = accumulator.failWithStreamError();

        assertThat(outcome.status()).isEqualTo(AiChatGenerationOutcome.Status.STREAM_ERROR);
        assertThat(outcome.isSuccess()).isFalse();
    }

    @Test
    void 기한_초과로_끊은_생성은_실패로_판정한다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));

        assertThat(accumulator.failWithTimeout().status())
                .isEqualTo(AiChatGenerationOutcome.Status.TIMED_OUT);
    }

    @Test
    void 종료_신호가_겹쳐_들어와도_먼저_도착한_판정_하나만_유지한다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        AiChatGenerationOutcome first = accumulator.completeNormally();
        AiChatGenerationOutcome afterError = accumulator.failWithStreamError();
        AiChatGenerationOutcome afterTimeout = accumulator.failWithTimeout();

        assertThat(first.isSuccess()).isTrue();
        assertThat(afterError).isSameAs(first);
        assertThat(afterTimeout).isSameAs(first);
        assertThat(accumulator.isTerminated()).isTrue();
    }

    @Test
    void 오류로_먼저_끝난_뒤에_온_정상_완료_신호는_성공으로_바꾸지_못한다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));
        accumulator.accept(AiChatStreamChunk.ofFinishReason("STOP"));
        accumulator.accept(AiChatStreamChunk.ofUsage(10, 5, 15));

        AiChatGenerationOutcome failed = accumulator.failWithStreamError();

        assertThat(accumulator.completeNormally()).isSameAs(failed);
        assertThat(failed.status()).isEqualTo(AiChatGenerationOutcome.Status.STREAM_ERROR);
    }

    @Test
    void 실패로_끝나도_받은_데까지의_본문은_결과에_남는다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));

        // 저장에 쓰라는 뜻이 아니다 — 실패 시 본문은 저장하지 않는다(설계 정본 §6.3).
        // 로그·운영 확인에 쓸 수 있도록 남길 뿐이다.
        assertThat(accumulator.failWithStreamError().content()).isEqualTo("이 책은");
    }

    @Test
    void 누적_본문은_생성_도중에도_읽을_수_있다() {
        AiChatGenerationAccumulator accumulator = new AiChatGenerationAccumulator();
        accumulator.accept(AiChatStreamChunk.ofDelta("이 책은"));

        assertThat(accumulator.accumulatedContent()).isEqualTo("이 책은");
        assertThat(accumulator.isTerminated()).isFalse();
    }
}
