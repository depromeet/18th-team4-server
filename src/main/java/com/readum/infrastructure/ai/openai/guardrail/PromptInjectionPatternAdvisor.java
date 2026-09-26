package com.readum.infrastructure.ai.openai.guardrail;

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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 정규식 기반 prompt-injection / jailbreak 패턴 차단 advisor.
 *
 * SafeGuardAdvisor 는 substring 매칭만 하기 때문에 "ignore previous instructions" 같이 변형이 잦은 패턴을 잡기 어렵다.
 * 이 advisor 는 prompt 의 USER role 메시지 중 가장 마지막(현재 사용자 입력) 한 건만 검사하여 다음을 동시에 방지한다.
 *  - system prompt 에 포함된 자체 보안 정책 문구가 오탐되는 것
 *  - 멀티턴 대화에서 과거 USER 발화와 현재 발화 사이 경계에 우연히 패턴이 매칭되는 것
 *
 * 차단 시 LLM 호출 없이 미리 정의된 한국어 거부 메시지를 응답으로 반환한다.
 */
@Slf4j
public class PromptInjectionPatternAdvisor implements CallAdvisor, StreamAdvisor {

    private static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private final List<Pattern> patterns;
    private final String failureResponse;
    private final int order;

    public PromptInjectionPatternAdvisor(List<String> patterns, String failureResponse, int order) {
        this.patterns = patterns.stream()
                .map(p -> Pattern.compile(p, PATTERN_FLAGS))
                .toList();
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
        int matchedIdx = findMatchIndex(request);
        if (matchedIdx >= 0) {
            logBlock(matchedIdx, extractLatestUserText(request));
            return failureResponse(request);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        int matchedIdx = findMatchIndex(request);
        if (matchedIdx >= 0) {
            logBlock(matchedIdx, extractLatestUserText(request));
            return Flux.just(failureResponse(request));
        }
        return chain.nextStream(request);
    }

    private int findMatchIndex(ChatClientRequest request) {
        String userText = extractLatestUserText(request);
        if (userText.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(userText).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 가장 마지막 USER role 메시지(현재 사용자 입력)만 추출한다.
     * 멀티턴 대화에서 과거 USER 발화 + 현재 USER 발화를 concat 하면
     * 경계 부분에서 우연히 패턴이 매칭되어 무고한 사용자가 차단될 위험이 있다.
     */
    private String extractLatestUserText(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    private ChatClientResponse failureResponse(ChatClientRequest request) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(failureResponse))))
                        .build())
                .context(Map.copyOf(request.context()))
                .build();
    }

    /**
     * 차단 로그에는 패턴 인덱스만 기록한다. 패턴 원문을 노출하면 공격자에게 어떤 패턴을
     * 쓰고 있는지 그대로 알려주는 셈이라 우회 패턴 작성이 쉬워진다.
     */
    private void logBlock(int patternIdx, String userText) {
        log.info("[Guardrail] {} blocked: patternIdx={}, length={}",
                getName(), patternIdx, userText.length());
    }
}
