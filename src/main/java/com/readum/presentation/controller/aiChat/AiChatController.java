package com.readum.presentation.controller.aiChat;

import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.dto.AiChatSessionListResult;
import com.readum.domain.aiChat.dto.BookChatSessionsResult;
import com.readum.domain.aiChat.dto.MessageListResult;
import com.readum.domain.aiChat.dto.SummaryDraftCommand;
import com.readum.domain.aiChat.dto.SummaryDraftEligibilityResult;
import com.readum.domain.aiChat.service.AiChatMessageSearchService;
import com.readum.domain.aiChat.service.AiChatMessageSendService;
import com.readum.domain.aiChat.service.AiChatSessionCreateService;
import com.readum.domain.aiChat.service.AiChatSessionSearchService;
import com.readum.domain.aiChat.service.BookChatSessionSearchService;
import com.readum.domain.aiChat.service.SummaryDraftSearchService;
import com.readum.domain.aiChat.service.SummaryDraftService;
import com.readum.domain.aiChat.service.SummaryEditService;
import com.readum.domain.aiChat.stream.ChatDeliveryChannel;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.service.SummarySearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.common.security.AuthenticatedUserId;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionCreateResponse;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionListRequest;
import com.readum.presentation.controller.aiChat.dto.AiChatSessionListResponse;
import com.readum.presentation.controller.aiChat.dto.BookChatSessionsResponse;
import com.readum.presentation.controller.aiChat.dto.MessageListRequest;
import com.readum.presentation.controller.aiChat.dto.MessageListResponse;
import com.readum.presentation.controller.aiChat.dto.SendMessageRequest;
import com.readum.presentation.controller.aiChat.dto.SummaryDraftEligibilityResponse;
import com.readum.presentation.controller.aiChat.dto.SummaryEditRequest;
import com.readum.presentation.controller.aiChat.dto.SummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@Slf4j
@Tag(name = "AI 채팅", description = "AI 와 책 한 권에 대해 대화하는 채팅 세션 및 메시지 관리")
@RestController
@RequestMapping("/api/v1/ai-chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatSessionCreateService aiChatSessionCreateService;
    private final AiChatSessionSearchService aiChatSessionSearchService;
    private final AiChatMessageSendService aiChatMessageSendService;
    private final AiChatMessageSearchService aiChatMessageSearchService;
    private final SummaryDraftService summaryDraftService;
    private final SummaryEditService summaryEditService;
    private final SummarySearchService summarySearchService;
    private final SummaryDraftSearchService summaryDraftSearchService;
    private final BookChatSessionSearchService bookChatSessionSearchService;
    private final MessageStreamSseDelivery messageStreamSseDelivery;

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
            @AuthenticatedUserId Long userId,
            @Valid @RequestBody AiChatSessionCreateRequest request
    ) {
        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(request.toCommand(userId));
        return GlobalApiResponse.created(AiChatSessionCreateResponse.from(result));
    }

    @Operation(
            summary = "AI 채팅 세션 목록 조회",
            description = "선택한 도서(userBookId)에 대한 채팅 세션을 최근 채팅 날짜 내림차순으로 페이지네이션 조회한다. " +
                    "각 세션은 ACTIVE / SUMMARIZING / SUMMARIZED 상태로 구분된다 — " +
                    "SUMMARIZING 은 감상문 생성 중(메시지 전송 불가), SUMMARIZED 는 감상문이 완성되어 종료된 세션(영구히 대화 불가)을 의미한다. " +
                    "ACTIVE 세션만 대화를 이어갈 수 있다. " +
                    "lastChattedDate 는 마지막으로 노출된 메시지(USER/ASSISTANT, COMPLETED) 의 날짜이며, " +
                    "메시지가 없는 세션은 세션 생성 날짜로 fallback 된다. " +
                    "세션이 없으면 빈 배열로 200 응답한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "userBookId / page / size 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "해당 도서 없음 또는 소유권 없음")
    })
    @GetMapping("/sessions")
    public ResponseEntity<GlobalApiResponse<AiChatSessionListResponse>> getSessions(
            @AuthenticatedUserId Long userId,
            @Valid @ModelAttribute AiChatSessionListRequest request
    ) {
        AiChatSessionListResult result = aiChatSessionSearchService.findByUserBookId(request.toCommand(userId));
        return GlobalApiResponse.ok(AiChatSessionListResponse.from(result));
    }

    @Operation(
            summary = "책별 대화 세션 목록 조회",
            description = "한 권(userBookId)에 대해 만든 모든 채팅 세션을 책 정보(제목·출판연도·출판사·표지) 와 함께 조회한다. " +
                    "각 세션은 가장 최근 감상문 본문(latestSummaryContent, 없으면 null) 과 마지막 대화일(lastChattedDate, ISO) 을 포함하며, " +
                    "마지막 대화일 내림차순으로 정렬된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "본인 책장의 책이 아님")
    })
    @GetMapping("/books/{userBookId}/sessions")
    public ResponseEntity<GlobalApiResponse<BookChatSessionsResponse>> getBookChatSessions(
            @AuthenticatedUserId Long userId,
            @PathVariable Long userBookId
    ) {
        BookChatSessionsResult result = bookChatSessionSearchService.findByUserBook(userBookId, userId);
        return GlobalApiResponse.ok(BookChatSessionsResponse.from(result));
    }

    /**
     * 선행 처리는 요청 스레드(가상 스레드)에서 동기로 끝낸다 — 여기서 던진 예외는 SSE 시작 전이라
     * GlobalExceptionHandler 의 4xx/5xx JSON(429 는 Retry-After·X-RateLimit-* 헤더 포함)으로 나간다.
     *
     * <p>그 뒤로는 셋이 각자 돈다. <b>생성</b>은 서버가 소유한 구독이 소비하고, <b>전달</b>은 연결별 VT 가
     * 채널에서 꺼내 쓰며, <b>저장·정산</b>은 생성이 끝난 뒤 별도 VT 가 맡는다. 컨트롤러는 셋을 이어 주고
     * emitter 를 돌려줄 뿐 어느 것도 기다리지 않는다.
     *
     * <p>클라이언트 이탈은 생성을 취소하지 않는다 — 전달만 끝나고 소비·저장·정산은 끝까지 진행된다.
     */
    @Operation(
            summary = "메시지 전송 (SSE 응답)",
            description = """
                    사용자 메시지를 즉시 영속화한 뒤 AI 응답을 SSE 로 전달한다.
                    응답은 생성되는 대로 조각 단위로 내려온다.

                    **요청 식별자(requestId) 규약 — 클라이언트 필수 구현**:
                    - 한 번의 메시지 전송마다 클라이언트가 식별자(UUID 권장)를 발급한다.
                    - 응답이 끊겨 결과를 모를 때 **같은 요청을 다시 보낼 때는 같은 값을 그대로 유지**한다.
                      서버는 (사용자, requestId) 조합의 유일성을 DB 에서 보장하므로, 재전송해도 답변을 새로 만들거나
                      토큰을 다시 예약·과금하지 않는다.
                    - 같은 값이 다시 오면 그 요청이 진행 중이든 이미 끝났든(성공·실패) **409** 로 거절한다.
                      같은 값에 다른 본문을 실어 보내도 본문을 비교하지 않고 같은 요청으로 보아 409 로 거절한다.
                    - 실패한 요청을 사용자가 다시 시도할 때는 **새 식별자**를 발급해 보낸다.
                      서버가 매 재전송마다 식별자를 새로 발급해 주지 않는다.
                    - 끊긴 요청의 결과는 메시지 이력 조회로 확인한다.

                    SSE 이벤트 종류:
                    - **token**: 응답 텍스트 조각. payload `{"delta": "..."}` — 클라이언트는 받은 순서대로 이어붙인다.
                    - **done**: 조각을 끝까지 보낸 연결의 정상 종료. payload `{"tokenCount": {...}, "createdAt": "..."}`
                      본문을 싣지 않는다 — 이어붙인 부분 답변이 곧 최종 답변이다.
                    - **replace**: 전달이 밀려 조각 전송을 포기한 연결의 정상 종료.
                      payload `{"content": "...", "tokenCount": {...}, "createdAt": "..."}`
                      **클라이언트는 지금까지 표시한 부분 답변을 이어붙이지 말고 content 로 통째로 교체한다.**
                      중간 조각을 버렸기 때문에 화면의 부분 답변은 최종 답변과 다르다. 이 이벤트는 답변 저장·정산이
                      커밋된 뒤에만 나가므로, 여기 실린 본문은 이력 조회 결과와 같다.
                    - **error**: 스트림 비정상 종료. payload `{"code": "...", "message": "...", "rateLimit": {...}?}`
                      이때는 답변을 저장하지 않고 예약한 토큰도 되돌린다 — 부분 답변은 이력에 남지 않는다.

                    연결이 done/replace/error 없이 끊기면 결과는 메시지 이력 조회로 확인한다.
                    서버는 끊긴 연결을 다시 잇거나 이미 보낸 조각을 재생해 주지 않는다.

                    error 이벤트의 code:
                    - `AI_RATE_LIMIT_BURST`: OpenAI 의 일시적 한도 초과 (RPM/TPM). rateLimit payload 포함, 클라이언트 자동 재시도 가능.
                    - `AI_QUOTA_EXHAUSTED`: OpenAI quota 소진 (insufficient_quota). 사람 개입 전까지 회복 불가, rateLimit payload 없음.
                    - `AI_PROVIDER_ERROR` / `AI_PROVIDER_TRANSIENT` / `AI_STREAM_INTERRUPTED`: 그 외 OpenAI 호출 실패.

                    rate-limit 처리 경로 (둘이 다름에 유의):
                    - **자체 rate limiter (USER_RATE_LIMIT_EXCEEDED)**: SSE 시작 전에 동기 검사. 한도 초과 시 SSE 가 시작되지 않고
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
            @ApiResponse(responseCode = "200", description = "SSE 스트림 시작 (이후 token/done/replace/error 이벤트 흐름)"),
            @ApiResponse(responseCode = "400", description = "본문 검증 실패 / 감상문 생성 중(SESSION_LOCKED) 또는 완성·종료된(SESSION_ALREADY_SUMMARIZED) 세션"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음"),
            @ApiResponse(
                    responseCode = "503",
                    description = """
                            - `GUARDRAIL_MODERATION_UNAVAILABLE`: 입력 검사를 할 수 없는 상태.
                            - `SERVER_SHUTTING_DOWN`: 서버가 종료 절차에 들어가 새 요청을 받지 않는 상태.
                            - `AI_CHAT_CAPACITY_EXCEEDED`: 이 서버가 동시에 처리할 수 있는 턴 수의 상한에 닿은 상태.
                              진행 중인 턴이 끝나면 다시 받으므로 잠시 뒤 재시도하면 된다 (Retry-After 5초).

                            셋 다 SSE 가 시작되기 전이라 JSON 으로 응답하고, Retry-After 헤더가 함께 온다."""),
            @ApiResponse(
                    responseCode = "409",
                    description = """
                            - `DUPLICATE_TURN_REQUEST`: 이미 접수된 requestId. 진행 중·성공·실패 어느 상태든
                              같은 식별자로는 답변을 새로 만들거나 토큰을 다시 예약·과금하지 않는다.
                              재전송이라면 그대로 두고 이력 조회로 결과를 확인하고,
                              사용자가 새 생성을 원한다면 새 식별자로 다시 요청한다.
                            - `TURN_REQUEST_ALREADY_FINISHED`: 접수는 됐지만 기한이 지나 만료 복구가 이미 끝낸 요청.
                              선행 처리가 길게 지연된 뒤 예약 단계에 도착한 경우다. 새 식별자로 다시 요청한다."""),
            @ApiResponse(
                    responseCode = "429",
                    description = """
                            pre-stream 단계의 호출 한도 초과. 자체 rate limiter (USER_RATE_LIMIT_EXCEEDED) 가
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
    public SseEmitter sendMessage(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        // 선행 처리(진행 목록 등록·중복 판정·관문)는 요청 스레드에서 동기로 끝난다 —
        // 거절은 SSE 시작 전이라 GlobalExceptionHandler 가 4xx/5xx JSON 으로 내보낸다.
        AiChatMessageSendService.PreparedChatTurn turn =
                aiChatMessageSendService.prepare(request.toCommand(userId, sessionId));

        // 전달 통로를 먼저 열고(전달 기한의 시작점), 생성 구독을 건 뒤, 전달 VT 를 띄운다.
        // 생성을 먼저 시작해도 전달이 늦지 않는다 — 그 사이의 델타는 채널이 받아 두고 전달 VT 가 꺼내 간다.
        // 반대 순서로 두면 전달 VT 가 생성이 시작될 때까지 빈 채널에서 기다리기만 한다.
        ChatDeliveryChannel deliveryChannel = messageStreamSseDelivery.openChannel();
        aiChatMessageSendService.generateAndDeliver(turn, deliveryChannel);
        return messageStreamSseDelivery.start(deliveryChannel, sessionId);
    }

    @Operation(
            summary = "세션의 메시지 이력 조회 (페이지네이션)",
            description = "createdAt 내림차순으로 메시지 이력을 페이지네이션 조회한다. " +
                    "스트림 중단된 FAILED 부분 응답은 응답에서 제외된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "page/size 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음")
    })
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<GlobalApiResponse<MessageListResponse>> getMessages(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId,
            @Valid @ModelAttribute MessageListRequest request
    ) {
        MessageListResult result = aiChatMessageSearchService.findBySessionId(request.toCommand(userId, sessionId));
        return GlobalApiResponse.ok(MessageListResponse.from(result));
    }

    @Operation(
            summary = "감상문 조회",
            description = """
                    세션의 감상문(제목·본문)을 조회한다.

                    응답 분기:
                    - 세션이 감상문 생성 중: 409 SUMMARY_IN_PROGRESS — 잠시 후 재시도 필요
                    - 감상문 존재: 200 — 감상문 정상 반환
                    - 감상문 없음: 404

                    감상문이 완성되면 세션은 종료(LOCKED)되어 더 대화할 수 없고, 세션당 감상문은 하나만 존재한다(1:1).
                    생성에 실패하면 감상문 행을 남기지 않으므로, 생성 중(409)에서 결과 없음(404)으로 바뀐다 — 이때 세션은 ACTIVE 로 남아 다시 생성을 요청할 수 있다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "감상문 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음, 소유권 없음, 또는 감상문 생성 이력 없음"),
            @ApiResponse(responseCode = "409", description = "감상문 생성 중")
    })
    @GetMapping("/sessions/{sessionId}/summary")
    public ResponseEntity<GlobalApiResponse<SummaryResponse>> getSummary(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId
    ) {
        SummaryResult result = summarySearchService.findBySessionId(sessionId, userId);
        return GlobalApiResponse.ok(SummaryResponse.from(result));
    }

    @Operation(
            summary = "감상문 수정",
            description = "세션의 최신 감상문 제목과 본문을 수정한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "감상문 수정 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음, 소유권 없음, 또는 감상문 없음")
    })
    @PutMapping("/sessions/{sessionId}/summary")
    public ResponseEntity<GlobalApiResponse<SummaryResponse>> editSummary(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SummaryEditRequest request
    ) {
        SummaryResult result = summaryEditService.execute(request.toCommand(userId, sessionId));
        return GlobalApiResponse.ok(SummaryResponse.from(result));
    }

    @Operation(
            summary = "감상문 초안 생성 가능 여부 조회",
            description = "AI 채팅 세션이 감상문 초안 생성 조건(종료되지 않음 + 생성 진행 중이 아님 + 누적 토큰 충족)을 만족하는지 검사한다. " +
                    "부수 효과 없이 가능 여부와 사유만 반환한다. " +
                    "세션 미존재 또는 본인 소유가 아닌 세션은 404로 응답한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가능 여부 조회 성공 (eligible=false 일 수 있음)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션을 찾을 수 없음 또는 본인 소유가 아님")
    })
    @GetMapping("/sessions/{sessionId}/summary-draft/eligibility")
    public ResponseEntity<GlobalApiResponse<SummaryDraftEligibilityResponse>> getSummaryDraftEligibility(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId
    ) {
        SummaryDraftEligibilityResult result = summaryDraftSearchService.findEligibility(sessionId, userId);
        return GlobalApiResponse.ok(SummaryDraftEligibilityResponse.from(result));
    }

    @Operation(
            summary = "감상문 초안 생성 요청",
            description = """
                    AI 채팅 세션의 대화 내용을 바탕으로 감상문 생성 작업을 큐에 적재하고 즉시 202를 반환한다.
                    실제 생성은 백그라운드 워커가 OpenAI 호출 한도에 맞춰 처리한다.
                    생성 결과는 GET /sessions/{sessionId}/summary 로 폴링하여 확인한다.
                    워커가 생성을 시작하면 세션은 잠겨 메시지 전송이 차단되고, 생성에 성공하면 세션은 종료(SUMMARIZED)되어 더 대화할 수 없다(세션당 감상문 1개).
                    생성에 실패하면 세션은 ACTIVE 로 남아 다시 요청할 수 있다.
                    이미 완성·종료된 세션에 다시 요청하면 409 로 거부된다.
                    누적 토큰이 임계값에 미치지 못하면 422 를 반환한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "생성 요청 접수. 백그라운드에서 진행 중"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "세션 없음 또는 소유권 없음"),
            @ApiResponse(responseCode = "409", description = "감상문 생성 중(진행 중) 또는 이미 완성·종료된 세션"),
            @ApiResponse(responseCode = "422", description = "누적 토큰 부족으로 초안 생성 불가")
    })
    @PostMapping("/sessions/{sessionId}/summary-draft")
    public ResponseEntity<Void> createSummaryDraft(
            @AuthenticatedUserId Long userId,
            @PathVariable Long sessionId
    ) {
        summaryDraftService.execute(new SummaryDraftCommand(userId, sessionId));
        return ResponseEntity.accepted().build();
    }
}
