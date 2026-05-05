package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.aiChat.service.AiChatMessageSearchService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateResponse;
import com.readum.presentation.controller.aiChat.dto.MessageListRequest;
import com.readum.presentation.controller.aiChat.dto.MessageListResponse;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import com.readum.presentation.controller.aiChat.dto.SummaryDraftResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@Tag(name = "AI 채팅", description = "AI 와 책 한 권에 대해 대화하는 채팅 세션 및 메시지 관리")
@RestController
@RequestMapping("/api/v1/ai-chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatSessionCreateService aiChatSessionCreateService;
    private final AiChatMessageSendService aiChatMessageSendService;
    private final AiChatMessageSearchService aiChatMessageSearchService;
    private final SummaryDraftService summaryDraftService;
    private final MessageStreamSseSerializer messageStreamSseSerializer;

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
            description = """
                    사용자 메시지를 즉시 영속화한 뒤 AI 응답을 SSE 로 스트리밍한다.

                    SSE 이벤트 종류:
                    - **token**: 실시간 텍스트 청크. payload `{"delta": "..."}`
                    - **done**: 스트림 정상 종료. payload `{"messageId": ..., "tokenCount": {...}, "createdAt": "..."}`
                    - **error**: 스트림 비정상 종료. payload `{"code": "...", "message": "...", "rateLimit": {...}?}`

                    error 이벤트의 code:
                    - `AI_RATE_LIMIT_BURST`: OpenAI 의 일시적 한도 초과 (RPM/TPM). rateLimit payload 포함, 클라이언트 자동 재시도 가능.
                    - `AI_QUOTA_EXHAUSTED`: OpenAI quota 소진 (insufficient_quota). 사람 개입 전까지 회복 불가, rateLimit payload 없음.
                    - `AI_PROVIDER_ERROR` / `AI_PROVIDER_TRANSIENT` / `AI_STREAM_INTERRUPTED`: 그 외 OpenAI 호출 실패.

                    rate-limit 처리 경로 (둘이 다름에 유의):
                    - **자체 rate limiter (USER_RATE_LIMIT_BURST)**: SSE 시작 전에 동기 검사. 한도 초과 시 SSE 가 시작되지 않고
                      HTTP 429 + Retry-After / X-RateLimit-* 헤더 + 에러 JSON 으로 응답된다 (아래 429 spec 참고).
                    - **OpenAI 의 429** (AI_RATE_LIMIT_BURST / AI_QUOTA_EXHAUSTED): mid-stream 발생이라
                      status 200 SSE 안에서 error 이벤트로 노출. HTTP 응답이 이미 commit 되어 X-RateLimit-* 헤더는 사용 불가하고,
                      동일 정보는 error.rateLimit payload 로 운반된다.

                    rateLimit payload (BURST 일 때만):
                    ```
                    {
                      "retryAfterSeconds": 13,
                      "limitRequests": 5000,
                      "remainingRequests": 0,
                      "resetRequestsSeconds": 12,
                      "resetTokensSeconds": 90
                    }
                    ```
                    채워진 필드만 포함되고 누락 필드는 키 자체가 생략된다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 시작 (이후 token/done/error 이벤트 흐름)"),
            @ApiResponse(responseCode = "400", description = "본문 검증 실패 / 종료된 세션"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음"),
            @ApiResponse(
                    responseCode = "429",
                    description = """
                            pre-stream 단계의 호출 한도 초과. 자체 rate limiter (USER_RATE_LIMIT_BURST) 가
                            SSE 시작 전에 동기적으로 검사하므로, 한도를 넘으면 SSE 가 시작되지 않고
                            HTTP 429 + Retry-After / X-RateLimit-* 헤더 + 에러 JSON 이 응답된다.

                            대조적으로 OpenAI 의 429 (AI_RATE_LIMIT_BURST / AI_QUOTA_EXHAUSTED) 는 mid-stream 으로
                            발생하므로, 이미 commit 된 status 200 SSE 안에서 error 이벤트로 노출된다 (description 본문 참고).
                            """,
                    headers = {
                            @Header(name = "Retry-After",
                                    description = "재시도까지 대기할 초 (정수)",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Limit-Requests",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Limit-Tokens",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Remaining-Requests",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Remaining-Tokens",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Reset-Requests",
                                    description = "요청 한도 리셋까지 남은 시간 (초)",
                                    schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Reset-Tokens",
                                    description = "토큰 한도 리셋까지 남은 시간 (초)",
                                    schema = @Schema(type = "integer"))
                    }
            )
    })
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> sendMessage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        Flux<ServerSentEvent<String>> stream = aiChatMessageSendService
                .execute(request.toCommand(userId, sessionId))
                .map(messageStreamSseSerializer::toServerSentEvent);
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

    @Operation(
            summary = "감상문 초안 생성",
            description = "AI 채팅 세션의 대화 내용을 바탕으로 감상문 초안(제목·본문·인상 깊은 구절)을 생성하고 세션을 종료한다. " +
                    "누적 토큰이 임계값에 미치지 못하면 422 를 반환한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "감상문 초안 생성 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음"),
            @ApiResponse(responseCode = "409", description = "이미 감상문이 작성된 세션"),
            @ApiResponse(responseCode = "422", description = "누적 토큰 부족으로 초안 생성 불가")
    })
    @PostMapping("/sessions/{sessionId}/summary-draft")
    public ResponseEntity<GlobalApiResponse<SummaryDraftResponse>> createSummaryDraft(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long sessionId
    ) {
        SummaryDraftResult result = summaryDraftService.execute(sessionId, userId);
        return GlobalApiResponse.ok(SummaryDraftResponse.from(result));
    }
}
