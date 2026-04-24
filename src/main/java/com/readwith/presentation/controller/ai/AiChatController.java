package com.readwith.presentation.controller.ai;

import com.readwith.domain.ai.dto.AiChatResult;
import com.readwith.domain.ai.service.AiChatService;
import com.readwith.presentation.common.ApiResponse;
import com.readwith.presentation.controller.ai.dto.AiChatRequest;
import com.readwith.presentation.controller.ai.dto.AiChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AiChatResponse>> chat(@RequestBody AiChatRequest request) {
        AiChatResult result = aiChatService.execute(request.toCommand());
        return ApiResponse.ok(AiChatResponse.from(result));
    }
}
