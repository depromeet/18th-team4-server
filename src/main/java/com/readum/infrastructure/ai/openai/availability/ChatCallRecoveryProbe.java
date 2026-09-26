package com.readum.infrastructure.ai.openai.availability;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiAvailability;
import com.readum.domain.exception.ExternalApiException;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 단발 호출로 사는 기능(감상문 · 컨텍스트 요약 · 제목)의 복구 확인.
 *
 * <p>세 기능은 프로젝트 키만 다르고 호출 모양은 같아서 한 클래스로 두고, 기능마다 인스턴스를 하나씩 만든다 —
 * 클래스를 셋으로 나누면 같은 코드가 셋으로 불어나고, 한 인스턴스가 기능을 인자로 받으면 호출은 A 프로젝트로
 * 나가고 상태는 B 기능에 쌓이는 실수가 생긴다.
 *
 * <p>확인 요청은 가장 작은 것이다 — 짧은 사용자 메시지 하나, 출력 상한 몇 토큰. 그래도 실제 프로젝트 키와
 * 실제 모델, 실제 엔드포인트를 쓰므로 "그 경로가 지금 성립하는가" 를 그대로 답한다.
 */
class ChatCallRecoveryProbe extends CapabilityRecoveryProbe {

    /** 확인용 프롬프트. 내용에 뜻은 없고, 짧을수록 좋다(입력 토큰도 그 프로젝트의 한도를 쓴다). */
    private static final String PROBE_PROMPT = "ping";

    private final ChatModel chatModel;

    ChatCallRecoveryProbe(AiAvailability.Capability capability, ChatModel chatModel) {
        super(capability);
        this.chatModel = chatModel;
    }

    /**
     * HTTP 200 만으로 성공을 판정하지 않는다 — 생성 결과가 실려 있는지까지 본다.
     * 응답 껍데기만 받고 "살아났다" 고 판정하면, 그 뒤 실제 작업들이 같은 빈 응답에 줄줄이 실패한다.
     */
    @Override
    protected void callProvider() {
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(PROBE_PROMPT)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ExternalApiException(AiChatErrorCode.AI_PROVIDER_ERROR);
        }
    }
}
