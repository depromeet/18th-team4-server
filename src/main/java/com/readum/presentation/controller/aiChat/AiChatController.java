package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.aiChat.dto.AiChatRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
