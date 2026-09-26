package com.readum.infrastructure.ai.openai;

import com.readum.infrastructure.ai.openai.ratelimit.OpenAiProject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 프로젝트별로 실제 부르는 모델 이름. 게이트 버킷 키·quota 쿨다운 키·오류 분류가 모두 "어느 프로젝트의 어느 모델" 로
 * 상태를 나누므로, 그 짝을 한 곳에서만 정한다 — 흩어지면 호출은 A 모델로 나가고 상태는 B 모델 키에 쌓인다.
 *
 * <p>지금은 채팅·감상문·컨텍스트 요약·제목이 같은 대화 모델을 쓰고, moderation 만 따로다.
 * 프로젝트마다 다른 모델을 쓰게 되면 여기만 바꾼다.
 */
@Component
public class OpenAiModelNames {

    private final String chatModel;
    private final String moderationModel;

    public OpenAiModelNames(
            @Value("${spring.ai.openai.chat.options.model}") String chatModel,
            @Value("${spring.ai.openai.moderation.options.model:omni-moderation-latest}") String moderationModel
    ) {
        this.chatModel = chatModel;
        this.moderationModel = moderationModel;
    }

    public String of(OpenAiProject project) {
        return project == OpenAiProject.MODERATION ? moderationModel : chatModel;
    }
}
