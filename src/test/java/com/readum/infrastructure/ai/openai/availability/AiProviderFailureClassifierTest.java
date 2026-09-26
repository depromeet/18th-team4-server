package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.exception.AiDependencyUnavailableException;
import com.readum.domain.exception.ExternalApiException;
import com.readum.domain.exception.RateLimitInfo;
import com.readum.domain.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무엇을 "공급자가 지금 응답을 못 주는 상황" 으로 셀지의 계약. 이 판정이 곧 차단 여부를 정하므로,
 * 세지 말아야 할 것(그 요청 하나의 문제, 우리 쪽 문제)을 세면 멀쩡한 공급자를 우리가 막게 된다.
 */
class AiProviderFailureClassifierTest {

    private final AiProviderFailureClassifier classifier = new AiProviderFailureClassifier();

    @Test
    void 결제_소진은_결제_종류로_세고_공급자가_준_재시도_간격을_보존한다() {
        TooManyRequestsException quotaExhausted = new TooManyRequestsException(
                AiChatErrorCode.AI_QUOTA_EXHAUSTED,
                RateLimitInfo.retryAfterOnly(Duration.ofSeconds(90)));

        AiProviderFailureClassifier.Classification classification = classifier.classify(quotaExhausted);

        assertThat(classification.kind()).isEqualTo(AiProviderFailureKind.QUOTA);
        assertThat(classification.retryAfter()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void 공급자의_한도_초과_응답은_한도_종류로_센다() {
        assertThat(classifier.classify(new TooManyRequestsException(
                AiChatErrorCode.AI_PROVIDER_RATE_LIMITED, RateLimitInfo.empty())).kind())
                .isEqualTo(AiProviderFailureKind.RATE_LIMIT);
    }

    @Test
    void 우리_쪽_제한이_거절한_것은_공급자_실패가_아니다() {
        // 사용자 폭주 가드·토큰 예산의 거절은 공급자가 아무 말도 하지 않은 상태다. 이것을 실패로 세면
        // 우리가 스스로 막을 때마다 공급자를 죽은 것으로 판정하게 된다.
        assertThat(classifier.classify(new TooManyRequestsException(
                AiChatErrorCode.USER_RATE_LIMIT_EXCEEDED, RateLimitInfo.empty())).kind())
                .isEqualTo(AiProviderFailureKind.NOT_COUNTED);
    }

    @Test
    void 인증_권한_오류는_인증_종류로_센다() {
        assertThat(classifier.classify(new AiDependencyUnavailableException(
                AiChatErrorCode.AI_PROVIDER_AUTH_ERROR)).kind())
                .isEqualTo(AiProviderFailureKind.AUTH);
    }

    @Test
    void 우리가_스스로_막아_던진_차단은_다시_세지_않는다() {
        // 차단 중이라 시작도 못 한 호출을 실패로 세면, 막혀 있다는 사실 하나가 계속 자기를 되먹여 차단을 연장한다.
        assertThat(classifier.classify(new AiDependencyUnavailableException(
                AiChatErrorCode.AI_PROVIDER_UNAVAILABLE)).kind())
                .isEqualTo(AiProviderFailureKind.NOT_COUNTED);
    }

    @Test
    void 서버_오류와_연결_실패와_응답_없음은_일시_실패로_센다() {
        assertThat(classifier.classify(new TransientAiException("500")).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
        assertThat(classifier.classify(new ResourceAccessException("connect timed out")).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
        assertThat(classifier.classify(new IOException("connection reset")).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
        assertThat(classifier.classify(new TimeoutException("무응답")).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
        assertThat(classifier.classify(new ExternalApiException(AiChatErrorCode.AI_STREAM_INTERRUPTED)).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
    }

    @Test
    void 그_요청_하나의_입력_오류는_세지_않는다() {
        assertThat(classifier.classify(new NonTransientAiException("400 context_length_exceeded")).kind())
                .isEqualTo(AiProviderFailureKind.NOT_COUNTED);
    }

    @Test
    void 우리_쪽_내부_오류는_세지_않는다() {
        // 우리 버그를 공급자 장애로 오인해 전 기능을 막는 쪽이, 한 번 더 실패하는 쪽보다 나쁘다.
        assertThat(classifier.classify(new IllegalStateException("DB 매핑 오류")).kind())
                .isEqualTo(AiProviderFailureKind.NOT_COUNTED);
    }

    @Test
    void 공급자가_준_재시도_간격이_있으면_분류에_실어_보낸다() {
        // 5xx·과부하도 Retry-After 를 존중한다 — 차단 시간을 기본값으로 잡으면 공급자 말과 어긋난다.
        com.readum.infrastructure.ai.openai.OpenAiTransientException overloaded =
                new com.readum.infrastructure.ai.openai.OpenAiTransientException(
                        "OpenAI 503", java.time.Duration.ofSeconds(45), "server_is_overloaded");

        AiProviderFailureClassifier.Classification classification = classifier.classify(overloaded);

        assertThat(classification.kind()).isEqualTo(AiProviderFailureKind.TRANSIENT);
        assertThat(classification.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(45));
    }

    @Test
    void 감싸인_원인까지_따라_내려가_분류한다() {
        // Spring AI·WebClient 는 실제 원인을 한두 겹 감싸 던진다 — 겉만 보면 전부 "알 수 없는 오류" 가 된다.
        assertThat(classifier.classify(new RuntimeException("wrapper", new IOException("connection reset"))).kind())
                .isEqualTo(AiProviderFailureKind.TRANSIENT);
    }
}
