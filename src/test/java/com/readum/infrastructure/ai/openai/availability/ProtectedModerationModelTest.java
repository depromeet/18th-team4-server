package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.aiChat.out.AiAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 검토 응답의 <b>모양</b>을 확인하는 공통 경계. 판정이 실려 있지 않은 응답을 그대로 돌려주면
 * 입력 검토와 출력 검토가 모두 "걸린 것이 없다" 로 읽어 검사받지 않은 내용을 통과시킨다.
 */
class ProtectedModerationModelTest {

    private final AiProviderCircuitBreaker circuitBreaker = mock(AiProviderCircuitBreaker.class);

    private ProtectedModerationModel modelReturning(ModerationResponse response) {
        ModerationModel delegate = mock(ModerationModel.class);
        given(delegate.call(any(ModerationPrompt.class))).willReturn(response);
        given(circuitBreaker.admit(any())).willReturn(
                new AiProviderCircuitBreaker.CallPermit(
                        AiAvailability.Capability.MODERATION, 0L, System.nanoTime()));
        AiProviderCallGuard guard = new AiProviderCallGuard(
                circuitBreaker, new AiProviderFailureClassifier());
        return new ProtectedModerationModel(delegate, guard);
    }

    @Test
    void 판정이_실리지_않은_응답은_통과가_아니라_실패로_본다() {
        ProtectedModerationModel model = modelReturning(new ModerationResponse(
                new org.springframework.ai.moderation.Generation(
                        Moderation.builder().id("modr-test").model("omni-moderation-latest").results(List.of()).build())));

        assertThatThrownBy(() -> model.call(new ModerationPrompt("검사받아야 할 본문")))
                .as("빈 판정을 성공으로 읽으면 검사받지 않은 내용이 그대로 지나간다")
                .isInstanceOf(AiDependencyUnavailableException.class);
    }

    @Test
    void 판정이_실린_응답은_그대로_돌려준다() {
        ModerationResponse healthy = new ModerationResponse(
                new org.springframework.ai.moderation.Generation(
                        Moderation.builder().id("modr-test").model("omni-moderation-latest").results(List.of(verdict())).build()));

        assertThat(modelReturning(healthy).call(new ModerationPrompt("정상 본문"))).isSameAs(healthy);
    }

    private static ModerationResult verdict() {
        return ModerationResult.builder()
                .flagged(false)
                .categories(Categories.builder().build())
                .categoryScores(CategoryScores.builder().build())
                .build();
    }

    /** 상태 기록을 그 자리에서 실행해 테스트를 결정적으로 만든다. */
    private static java.util.concurrent.ExecutorService sameThreadExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }
}
