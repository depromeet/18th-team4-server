package com.readwith.presentation.controller.ai;

import com.readwith.domain.ai.dto.AiChatResult;
import com.readwith.domain.ai.service.AiChatService;
import com.readwith.domain.ai.service.AiStreamChatService;
import com.readwith.presentation.common.ApiResponse;
import com.readwith.presentation.controller.ai.dto.AiChatRequest;
import com.readwith.presentation.controller.ai.dto.AiChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final AiStreamChatService aiStreamChatService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AiChatResponse>> chat(@RequestBody AiChatRequest request) {
        AiChatResult result = aiChatService.execute(request.toCommand());
        return ApiResponse.ok(AiChatResponse.from(result));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody AiChatRequest request) {
        return aiStreamChatService.stream(request.toCommand());
    }
}
