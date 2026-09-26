package com.readum.infrastructure.ai.openai.guardrail;

import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.ExternalApiException;
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
 *
 * 검토 호출이 실패하면 우회하지 않고 예외를 올려보낸다(fail-closed) — 자세한 이유는 isFlagged 주석 참조.
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

    /**
     * 출력 검토. <b>실패하면 검사를 건너뛰지 않고 예외를 그대로 올려보낸다(fail-closed).</b>
     *
     * <p>예전에는 검토 호출이 실패하면 "통과" 로 흘려보냈다. 그러면 검토 모델이 죽어 있는 동안 생성된 감상문이
     * 아무 검사도 받지 않은 채 저장된다 — 검사를 두는 이유가 바로 그 상황이라, 가장 필요할 때 검사가 사라지는 셈이다.
     * 지금은 실패를 그대로 올려보내 호출자가 정하게 한다. 감상문 생성은 작업 큐가 부르므로,
     * 공급자 사정이면 시도 횟수 없이 되돌려 다음에 다시 검토하고(작업이 보존된다),
     * 그 밖의 실패면 큐의 재시도 정책을 탄다. 검사를 건너뛴 결과가 저장되는 경우는 없다.
     */
    private boolean isFlagged(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        ModerationResponse response = moderationModel.call(new ModerationPrompt(text));
        ModerationResult result = Optional.ofNullable(response)
                .map(ModerationResponse::getResult)
                .map(org.springframework.ai.moderation.Generation::getOutput)
                .map(Moderation::getResults)
                .filter(list -> !list.isEmpty())
                .map(List::getFirst)
                .orElse(null);
        if (result == null) {
            // 판정이 없는 응답을 "걸린 것 없음" 으로 읽으면 검사받지 않은 본문이 그대로 저장된다.
            // 모델 경계(ProtectedModerationModel)가 먼저 걸러 주지만, 이 판단이 여기에도 남아 있어야
            // 다른 모델 구현을 끼웠을 때 조용히 통과하지 않는다.
            throw new ExternalApiException(AiChatErrorCode.GUARDRAIL_MODERATION_UNAVAILABLE);
        }
        return result.isFlagged();
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
