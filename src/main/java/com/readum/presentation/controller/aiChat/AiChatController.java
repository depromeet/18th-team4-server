package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.aiChat.dto.AiChatRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateResponse;
import com.readum.presentation.controller.aiChat.dto.SummaryDraftResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/ai-chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiStreamChatService aiStreamChatService;
    private final AiChatSessionCreateService aiChatSessionCreateService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AiChatSessionCreateResponse>> createSession(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AiChatSessionCreateRequest request
    ) {
        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(request.toCommand(userId));
        return ApiResponse.created(AiChatSessionCreateResponse.from(result));
    }

    @Operation(
            summary = "감상문 초안 생성",
            description = "AI 채팅 세션의 대화 내용을 바탕으로 감상문 초안(제목·본문·인상 깊은 구절)을 생성한다. " +
                    "대화량이 부족하면 422 Unprocessable Entity를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "감상문 초안 생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "대화량 부족으로 초안 생성 불가"),
    })
    @PostMapping("/sessions/{sessionId}/summary-draft")
    public ResponseEntity<ApiResponse<SummaryDraftResponse>> createSummaryDraft(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Long userId
    ) {
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chatStream(@Valid @RequestBody AiChatRequest request) {
        Flux<ServerSentEvent<String>> stream = aiStreamChatService.stream(request.toCommand())
                .map(text -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(text)
                        .build())
                .onErrorResume(e -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("error")
                                .data("통신 중 문제가 발생했습니다.")
                                .build()
                ));
        return ResponseEntity.ok(stream);
    }
}
