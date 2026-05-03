package com.readum.infrastructure.ai.openai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI Moderation API 기반 입력 가드레일.
 *
 * 사용자 입력에 대해 hate/harassment/sexual/violence/self-harm 등 카테고리를 분류하고
 * flagged 가 true 인 경우 LLM 호출 없이 한국어 거부 응답을 즉시 반환한다.
 *
 * 외부 API 호출이 1회 추가되므로 SafeGuard / 정규식 advisor 보다 뒤에 배치한다.
 */
@Slf4j
public class ModerationInputAdvisor implements CallAdvisor, StreamAdvisor {

    private final ModerationModel moderationModel;
    private final String failureResponse;
    private final int order;

    public ModerationInputAdvisor(ModerationModel moderationModel, String failureResponse, int order) {
        this.moderationModel = moderationModel;
        this.failureResponse = failureResponse;
        this.order = order;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (isFlagged(request)) {
            return failureResponse(request);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (isFlagged(request)) {
            return Flux.just(failureResponse(request));
        }
        return chain.nextStream(request);
    }

    private boolean isFlagged(ChatClientRequest request) {
        String userText = extractUserText(request);
        if (userText.isBlank()) {
            return false;
        }
        try {
            ModerationResponse response = moderationModel.call(new ModerationPrompt(userText));
            ModerationResult result = Optional.ofNullable(response.getResult())
                    .map(generation -> generation.getOutput())
                    .map(moderation -> moderation.getResults())
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.get(0))
                    .orElse(null);
            if (result == null) {
                return false;
            }
            if (result.isFlagged()) {
                log.info("[Guardrail] {} blocked: categories={}, length={}",
                        getName(), describeCategories(result.getCategories()), userText.length());
                return true;
            }
            return false;
        } catch (Exception e) {
            // Fail-open: moderation API 자체가 실패하면 LLM 호출은 진행하되 경고 로그.
            log.warn("[Guardrail] {} moderation API failure (fail-open): {}", getName(), e.getMessage());
            return false;
        }
    }

    private String describeCategories(Categories categories) {
        if (categories == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder("[");
        if (categories.isHate()) sb.append("hate,");
        if (categories.isHateThreatening()) sb.append("hate-threatening,");
        if (categories.isHarassment()) sb.append("harassment,");
        if (categories.isHarassmentThreatening()) sb.append("harassment-threatening,");
        if (categories.isSexual()) sb.append("sexual,");
        if (categories.isSexualMinors()) sb.append("sexual-minors,");
        if (categories.isViolence()) sb.append("violence,");
        if (categories.isViolenceGraphic()) sb.append("violence-graphic,");
        if (categories.isSelfHarm()) sb.append("self-harm,");
        if (categories.isSelfHarmIntent()) sb.append("self-harm-intent,");
        if (categories.isSelfHarmInstructions()) sb.append("self-harm-instructions,");
        sb.append("]");
        return sb.toString();
    }

    private String extractUserText(ChatClientRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message message : request.prompt().getInstructions()) {
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                if (text != null) {
                    sb.append(text).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    private ChatClientResponse failureResponse(ChatClientRequest request) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(failureResponse))))
                        .build())
                .context(Map.copyOf(request.context()))
                .build();
    }
}
