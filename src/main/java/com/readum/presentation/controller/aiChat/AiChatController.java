package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiStreamChatService;
import com.readum.domain.aiChat.service.SummaryDraftService;
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
    private final SummaryDraftService summaryDraftService;

    @Operation(
            summary = "AI 채팅 세션 생성",
            description = "userBookId에 해당하는 책장 도서를 기반으로 AI 채팅 세션을 생성한다. " +
                    "해당 사용자의 책장에 존재하지 않는 도서 ID를 전달하면 404를 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "세션 생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "책장에 존재하지 않는 도서"),
    })
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<AiChatSessionCreateResponse>> createSession(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AiChatSessionCreateRequest request
    ) {
        // TODO: 시큐리티 활성화 시 null 체크 복원 및 아래 줄 제거
        Long resolvedUserId = userId != null ? userId : 1L;
        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(request.toCommand(resolvedUserId));
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 감상문이 작성된 세션"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "누적 토큰 부족으로 초안 생성 불가"),
    })
    @PostMapping("/sessions/{sessionId}/summary-draft")
    public ResponseEntity<ApiResponse<SummaryDraftResponse>> createSummaryDraft(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal Long userId
    ) {
        SummaryDraftResult result = summaryDraftService.execute(sessionId);
        return ApiResponse.ok(SummaryDraftResponse.from(result));
    }

    @Operation(
            summary = "AI 채팅 스트리밍",
            description = "사용자 메시지를 받아 AI 응답을 Server-Sent Events(SSE) 스트림으로 반환한다. " +
                    "스트림 내 AI 오류는 HTTP 상태가 아닌 event:error 이벤트로 전달된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE 스트림 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
    })
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
