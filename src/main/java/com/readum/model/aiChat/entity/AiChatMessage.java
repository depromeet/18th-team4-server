package com.readum.model.aiChat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "ai_chat_message",
        indexes = {
                @Index(name = "idx_ai_chat_message_session_created_id", columnList = "session_id, created_at, id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class AiChatMessage {

    public enum Role {
        USER, ASSISTANT
    }

    /**
     * COMPLETED : USER 메시지(즉시 저장) 또는 정상 종료된 ASSISTANT 메시지.
     * FAILED    : 스트림 비정상 종료 시 부분 응답을 보존하기 위한 ASSISTANT 메시지 상태.
     *             컨텍스트 윈도우(findRecentForContextAssembly) 에서 자동 제외된다.
     * REJECTED  : 입력 가드레일에 차단된 USER 메시지. 감사 추적을 위해 저장은 하되,
     *             컨텍스트 윈도우·감상문 초안·세션 제목 생성 어떤 LLM 프롬프트에도 포함되지 않는다.
     */
    public enum Status {
        COMPLETED, FAILED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "quote_text", columnDefinition = "TEXT")
    private String quoteText;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    // 메시지별 토큰 크기(조립·요약 트리거용). USER=jtokkit 로컬 계산, ASSISTANT=API 실측 출력.
    // 호출 단위 usage(input/output/total_tokens)와 축이 다른 균일 컬럼.
    @Column(name = "token_count")
    private Integer tokenCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 짧은 형태 — token_count 를 모르는 조립용(주로 테스트). 프로덕션은 tokenCount 명시 형태를 쓴다. */
    public static AiChatMessage createUserMessage(Long sessionId, String content) {
        return createUserMessage(sessionId, content, null);
    }

    public static AiChatMessage createUserMessage(Long sessionId, String content, Integer tokenCount) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.USER,
                content,
                null,
                null,
                null,
                null,
                tokenCount,
                Status.COMPLETED,
                LocalDateTime.now()
        );
    }

    /**
     * 입력 가드레일에 차단된 USER 메시지. 감사 추적용으로 REJECTED 상태로 저장한다.
     * 거부 사유는 별도 컬럼 없이 로그로만 남긴다(이번 범위). 토큰·인용 없음.
     */
    public static AiChatMessage createUserMessageRejected(Long sessionId, String content) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.USER,
                content,
                null,
                null,
                null,
                null,
                null,
                Status.REJECTED,
                LocalDateTime.now()
        );
    }

    /** 짧은 형태 — token_count 를 실측 출력(outputTokens)으로 둔다(주로 테스트). */
    public static AiChatMessage createAssistantSuccess(
            Long sessionId, String content, Integer inputTokens, Integer outputTokens, Integer totalTokens
    ) {
        return createAssistantSuccess(sessionId, content, inputTokens, outputTokens, totalTokens, outputTokens);
    }

    public static AiChatMessage createAssistantSuccess(
            Long sessionId,
            String content,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Integer tokenCount
    ) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.ASSISTANT,
                content,
                null,
                inputTokens,
                outputTokens,
                totalTokens,
                tokenCount,
                Status.COMPLETED,
                LocalDateTime.now()
        );
    }

    /** 짧은 형태 — token_count 를 실측 출력(outputTokens)으로 둔다(주로 테스트). */
    public static AiChatMessage createAssistantFailed(
            Long sessionId, String partialContent, Integer inputTokens, Integer outputTokens, Integer totalTokens
    ) {
        return createAssistantFailed(sessionId, partialContent, inputTokens, outputTokens, totalTokens, outputTokens);
    }

    public static AiChatMessage createAssistantFailed(
            Long sessionId,
            String partialContent,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Integer tokenCount
    ) {
        return new AiChatMessage(
                null,
                sessionId,
                Role.ASSISTANT,
                partialContent,
                null,
                inputTokens,
                outputTokens,
                totalTokens,
                tokenCount,
                Status.FAILED,
                LocalDateTime.now()
        );
    }
}
