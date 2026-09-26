package com.readum.infrastructure.ai.openai.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI Moderation API 기반 출력 가드레일.
 *
 * - 비스트리밍 호출: LLM 응답 텍스트를 수집해 1회 moderation 호출, flag 시 거부 메시지로 교체.
 * - 스트리밍 호출: 모든 청크를 수신·누적한 뒤 1회 moderation 호출.
 *   * flag 가 아니면 누적된 청크를 그대로 다시 emit (스트리밍 UX 는 일부 손실).
 *   * flag 면 거부 메시지를 단일 청크로 emit.
 *
 * 외부 API 호출 1회 추가됨. enabled 가 false 인 경우 OpenAiConfig 에서 등록하지 않는다.
 */
@Slf4j
public class ModerationOutputAdvisor implements CallAdvisor, StreamAdvisor {

    private final ModerationModel moderationModel;
    private final String failureResponse;
    private final int order;

    public ModerationOutputAdvisor(ModerationModel moderationModel, String failureResponse, int order) {
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
        ChatClientResponse response = chain.nextCall(request);
        String text = extractAssistantText(response);
        if (isFlagged(text)) {
            log.info("[Guardrail] {} blocked output (call): length={}", getName(), text.length());
            return replaceWithFailure(request, response);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
                .collectList()
                .flatMapMany(chunks -> {
                    String accumulated = chunks.stream()
                            .map(this::extractAssistantText)
                            .reduce("", String::concat);
                    if (isFlagged(accumulated)) {
                        log.info("[Guardrail] {} blocked output (stream): length={}",
                                getName(), accumulated.length());
                        // 차단 시 마지막 chunk 의 context 를 유지한다.
                        // 그래야 체인 상위 advisor 들이 응답 전파 과정에서 누적시킨 메타데이터가 보존되어
                        // adviseCall 경로(replaceWithFailure)와 동일한 동작이 된다.
                        ChatClientResponse lastChunk = chunks.isEmpty() ? null : chunks.getLast();
                        return Flux.just(replaceWithFailure(request, lastChunk));
                    }
                    return Flux.fromIterable(chunks);
                });
    }

    private String extractAssistantText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return "";
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse.getResult() == null) {
            return "";
        }
        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output == null) {
            return "";
        }
        String text = output.getText();
        return text == null ? "" : text;
    }

    private boolean isFlagged(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            ModerationResponse response = moderationModel.call(new ModerationPrompt(text));
            ModerationResult result = Optional.ofNullable(response.getResult())
                    .map(org.springframework.ai.moderation.Generation::getOutput)
                    .map(Moderation::getResults)
                    .filter(list -> !list.isEmpty())
                    .map(List::getFirst)
                    .orElse(null);

            return result != null && result.isFlagged();
        } catch (Exception e) {
            log.warn("[Guardrail] {} moderation API failure (fail-open): {}", getName(), e.getMessage());
            return false;
        }
    }

    private ChatClientResponse replaceWithFailure(ChatClientRequest request, ChatClientResponse original) {
        Map<String, Object> context = original != null
                ? Map.copyOf(original.context())
                : Map.copyOf(request.context());

        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(failureResponse))))
                        .build())
                .context(context)
                .build();
    }
}
