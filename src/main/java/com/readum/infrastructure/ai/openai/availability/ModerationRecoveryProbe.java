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
 * 입력·출력 검토의 복구 확인 — 검토 엔드포인트에 짧은 문자열 하나를 보내고 <b>유효한 판정이 실려 있는지</b> 본다.
 *
 * <p>판정이 비어 있는 응답을 성공으로 보면 안 된다. 그 상태로 차단을 풀면, 검토를 통과했는지 알 수 없는
 * 입력이 "문제 없음" 으로 읽혀 그대로 모델에 나간다 — 검토 기능이 있으나 마나가 되는 가장 나쁜 실패다.
 * 그래서 판정이 없으면 확인 실패로 다룬다({@code ProtectedModerationModel} 이 실제 호출에서 하는 판단과 같다).
 */
class ModerationRecoveryProbe extends CapabilityRecoveryProbe {

    /** 확인용 입력. 검토 결과가 어떻게 나오든(통과든 차단이든) 판정이 실려 오기만 하면 된다. */
    private static final String PROBE_INPUT = "ping";

    private final ModerationModel moderationModel;

    ModerationRecoveryProbe(ModerationModel moderationModel) {
        super(AiAvailability.Capability.MODERATION);
        this.moderationModel = moderationModel;
    }

    @Override
    protected void callProvider() {
        ModerationResponse response = moderationModel.call(new ModerationPrompt(PROBE_INPUT));
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
    }
}
