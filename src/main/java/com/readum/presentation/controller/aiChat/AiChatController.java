package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.MessageStreamEvent;
import com.readum.domain.aiChat.service.AiChatMessageSearchService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateResponse;
import com.readum.presentation.controller.aiChat.dto.MessageListRequest;
import com.readum.presentation.controller.aiChat.dto.MessageListResponse;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Tag(name = "AI 채팅", description = "AI 와 책 한 권에 대해 대화하는 채팅 세션 및 메시지 관리")
@RestController
@RequestMapping("/api/v1/ai-chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatSessionCreateService aiChatSessionCreateService;
    private final AiChatMessageSendService aiChatMessageSendService;
    private final AiChatMessageSearchService aiChatMessageSearchService;
    private final ObjectMapper objectMapper;

    @Operation(
            summary = "AI 채팅 세션 생성",
            description = "사용자가 소유한 도서(userBookId)를 기반으로 새 채팅 세션을 생성한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "세션 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "소유하지 않은 도서")
    })
    @PostMapping("/sessions")
    public ResponseEntity<GlobalApiResponse<AiChatSessionCreateResponse>> createSession(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AiChatSessionCreateRequest request
    ) {
        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(request.toCommand(userId));
        return GlobalApiResponse.created(AiChatSessionCreateResponse.from(result));
    }

    @Operation(
            summary = "메시지 전송 (SSE 스트리밍 응답)",
            description = "사용자 메시지를 즉시 영속화한 뒤 AI 응답을 SSE 로 스트리밍한다. " +
                    "이벤트 종류: token (실시간 텍스트 청크), done (스트림 정상 종료 + 토큰 사용량), error (스트림 비정상 종료)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 시작"),
            @ApiResponse(responseCode = "400", description = "본문 검증 실패 / 종료된 세션"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음"),
            @ApiResponse(responseCode = "429", description = "AI 호출 한도 초과")
    })
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> sendMessage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        Flux<ServerSentEvent<String>> stream = aiChatMessageSendService
                .execute(request.toCommand(userId, sessionId))
                .map(this::toServerSentEvent);
        return ResponseEntity.ok(stream);
    }

    @Operation(
            summary = "세션의 메시지 이력 조회 (페이지네이션)",
            description = "createdAt 내림차순으로 메시지 이력을 페이지네이션 조회한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "page/size 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음")
    })
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<GlobalApiResponse<MessageListResponse>> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId,
            @Valid @ModelAttribute MessageListRequest request
    ) {
        MessageListResult result = aiChatMessageSearchService.findBySessionId(request.toCommand(userId, sessionId));
        return GlobalApiResponse.ok(MessageListResponse.from(result));
    }

    private ServerSentEvent<String> toServerSentEvent(MessageStreamEvent event) {
        return switch (event) {
            case MessageStreamEvent.Token token -> ServerSentEvent.<String>builder()
                    .event("token")
                    .data(toJson(Map.of("delta", token.delta())))
                    .build();
            case MessageStreamEvent.Done done -> ServerSentEvent.<String>builder()
                    .event("done")
                    .data(toJson(toDonePayload(done)))
                    .build();
            case MessageStreamEvent.Error error -> ServerSentEvent.<String>builder()
                    .event("error")
                    .data(toJson(Map.of("code", error.code(), "message", error.message())))
                    .build();
        };
    }

    private Map<String, Object> toDonePayload(MessageStreamEvent.Done done) {
        Map<String, Object> tokenCount = new LinkedHashMap<>();
        tokenCount.put("input", done.tokenCount().input());
        tokenCount.put("output", done.tokenCount().output());
        tokenCount.put("total", done.tokenCount().total());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", done.messageId());
        payload.put("tokenCount", tokenCount);
        payload.put("createdAt", done.createdAt().toString());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.error("SSE payload 직렬화 실패", ex);
            return "{}";
        }
    }
}
