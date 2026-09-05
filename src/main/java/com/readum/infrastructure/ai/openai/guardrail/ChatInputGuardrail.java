package com.readum.infrastructure.ai.openai.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 채팅 스트리밍 경로의 로컬 입력 검사기.
 *
 * <p>스트리밍은 {@code ChatClient} 를 거치지 않고 {@code OpenAiChatModel} 을 직접 호출하므로 advisor 체인이
 * 돌지 않는다. 그래서 {@code ChatClient} 에 달려 있던 입력 advisor 두 개의 판정을 여기서 그대로 수행한다.
 * <ul>
 *   <li>{@link PromptInjectionPatternAdvisor}: 마지막 USER 메시지 한 건을 정규식 패턴과 대조</li>
 *   <li>{@code SafeGuardAdvisor}(Spring AI 제공): 프롬프트 전체 본문에 금칙어가 들어 있는지 부분 문자열 대조</li>
 * </ul>
 *
 * <p><b>판정 동등성</b>(무엇을 차단하는가)은 advisor 와 같다 — 검사 대상, 검사 순서(정규식 먼저, 금칙어 다음),
 * 목록이 비었을 때 검사를 건너뛰는 규칙까지 맞췄다. <b>응답 동등성</b>(차단됐을 때 사용자가 무엇을 받는가) 역시
 * 지금은 advisor 와 같다 — 모델을 부르지 않고 거부 정본 문구를 답변으로 돌려준다.
 * 거절을 SSE 시작 전 JSON 으로 바꿀지는 아직 정해지지 않았으므로 여기서 앞질러 바꾸지 않는다.
 *
 * <p>두 검사 모두 로컬 계산이라 외부 호출 비용이 없다.
 */
@Slf4j
public class ChatInputGuardrail {

    private static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private final List<Pattern> injectionPatterns;
    private final List<String> sensitiveWords;
    private final String failureResponse;

    public ChatInputGuardrail(GuardrailProperties.Input input) {
        this.injectionPatterns = input.injectionPatterns().stream()
                .map(pattern -> Pattern.compile(pattern, PATTERN_FLAGS))
                .toList();
        this.sensitiveWords = List.copyOf(input.sensitiveWords());
        this.failureResponse = input.failureResponse();
    }

    /** 차단이면 거부 정본 문구를, 통과면 빈 값을 돌려준다. */
    public Optional<String> findBlockedFailureResponse(List<Message> promptMessages) {
        int matchedPatternIndex = findInjectionPatternIndex(promptMessages);
        if (matchedPatternIndex >= 0) {
            // 패턴 원문은 남기지 않는다 — 어떤 패턴에 걸렸는지 알려주면 우회 문구를 만들기 쉬워진다.
            log.info("[Guardrail] 입력 차단(정규식 패턴) patternIndex={} length={}",
                    matchedPatternIndex, latestUserText(promptMessages).length());
            return Optional.of(failureResponse);
        }
        if (containsSensitiveWord(promptMessages)) {
            log.info("[Guardrail] 입력 차단(금칙어)");
            return Optional.of(failureResponse);
        }
        return Optional.empty();
    }

    private int findInjectionPatternIndex(List<Message> promptMessages) {
        String userText = latestUserText(promptMessages);
        if (userText.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < injectionPatterns.size(); index++) {
            if (injectionPatterns.get(index).matcher(userText).find()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 가장 마지막 USER 메시지(이번 사용자 입력) 한 건만 뽑는다.
     * 여러 턴의 USER 발화를 이어 붙이면 그 경계에서 우연히 패턴이 걸려 무고한 사용자가 차단될 수 있고,
     * 시스템 프롬프트에 적힌 보안 정책 문구가 스스로 걸리는 일도 생긴다.
     */
    private String latestUserText(List<Message> promptMessages) {
        for (int index = promptMessages.size() - 1; index >= 0; index--) {
            Message message = promptMessages.get(index);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    /**
     * 금칙어는 프롬프트 전체 본문을 대상으로 본다 — {@code SafeGuardAdvisor} 가 {@code Prompt.getContents()}
     * (모든 메시지 본문을 구분자 없이 이어 붙인 문자열) 로 검사하므로 그 대상 범위를 그대로 맞춘다.
     * 금칙어 목록이 비어 있으면 advisor 자체가 등록되지 않았던 것과 같게 검사하지 않는다.
     */
    private boolean containsSensitiveWord(List<Message> promptMessages) {
        if (sensitiveWords.isEmpty()) {
            return false;
        }
        StringBuilder promptContents = new StringBuilder();
        for (Message message : promptMessages) {
            promptContents.append(message.getText());
        }
        String contents = promptContents.toString();
        return sensitiveWords.stream().anyMatch(contents::contains);
    }
}
