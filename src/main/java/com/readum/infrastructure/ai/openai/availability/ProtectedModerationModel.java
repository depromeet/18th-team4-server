package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.exception.ExternalApiException;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.List;
import java.util.Optional;

/**
 * 검토 모델을 공급자 상태 보호로 감싼 것. 입력 검토와 출력 검토가 같은 모델 빈을 쓰므로,
 * 모델 경계에서 한 번 감싸면 두 경로가 모두 보호된다 — 경로마다 따로 감싸면 한쪽을 빠뜨린다.
 */
public class ProtectedModerationModel implements ModerationModel {

    private final ModerationModel delegate;
    private final AiProviderCallGuard callGuard;

    public ProtectedModerationModel(ModerationModel delegate, AiProviderCallGuard callGuard) {
        this.delegate = delegate;
        this.callGuard = callGuard;
    }

    @Override
    public ModerationResponse call(ModerationPrompt request) {
        return callGuard.callProtected(
                AiAvailability.Capability.MODERATION, () -> requireVerdict(delegate.call(request)));
    }

    /** 판정이 실려 있지 않으면 공급자 응답 문제로 본다 — 통과로 읽히지 않게 오류로 바꾼다. */
    private ModerationResponse requireVerdict(ModerationResponse response) {
        ModerationResult verdict = Optional.ofNullable(response)
                .map(ModerationResponse::getResult)
                .map(org.springframework.ai.moderation.Generation::getOutput)
                .map(Moderation::getResults)
                .filter(results -> !results.isEmpty())
                .map(List::getFirst)
                .orElse(null);
        if (verdict == null) {
            throw new ExternalApiException(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);
        }
        return response;
    }
}
