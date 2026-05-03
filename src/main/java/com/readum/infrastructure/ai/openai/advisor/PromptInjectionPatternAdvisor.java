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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 정규식 기반 prompt-injection / jailbreak 패턴 차단 advisor.
 *
 * SafeGuardAdvisor 는 substring 매칭만 하기 때문에 "ignore previous instructions" 같이
 * 변형이 잦은 패턴을 잡기 어렵다. 이 advisor 는 사용자 메시지 부분(USER role)만 검사하여
 * 시스템 프롬프트에 포함된 자체 보안 정책 문구가 오탐되지 않도록 한다.
 *
 * 차단 시 LLM 호출 없이 미리 정의된 한국어 거부 메시지를 응답으로 반환한다.
 */
@Slf4j
public class PromptInjectionPatternAdvisor implements CallAdvisor, StreamAdvisor {

    private final List<Pattern> patterns;
    private final String failureResponse;
    private final int order;

    public PromptInjectionPatternAdvisor(List<String> patterns, String failureResponse, int order) {
        this.patterns = patterns.stream().map(Pattern::compile).toList();
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
        Pattern matched = findMatch(request);
        if (matched != null) {
            logBlock(matched, extractUserText(request));
            return failureResponse(request);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Pattern matched = findMatch(request);
        if (matched != null) {
            logBlock(matched, extractUserText(request));
            return Flux.just(failureResponse(request));
        }
        return chain.nextStream(request);
    }

    private Pattern findMatch(ChatClientRequest request) {
        String userText = extractUserText(request);
        if (userText.isEmpty()) {
            return null;
        }
        for (Pattern p : patterns) {
            if (p.matcher(userText).find()) {
                return p;
            }
        }
        return null;
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
        return sb.toString();
    }

    private ChatClientResponse failureResponse(ChatClientRequest request) {
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(failureResponse))))
                        .build())
                .context(Map.copyOf(request.context()))
                .build();
    }

    private void logBlock(Pattern pattern, String userText) {
        log.info("[Guardrail] {} blocked: pattern={}, length={}",
                getName(), pattern.pattern(), userText.length());
    }
}
