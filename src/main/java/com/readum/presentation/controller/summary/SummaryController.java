package com.readum.presentation.controller.summary;

import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.domain.summary.service.SummaryHistorySearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.summary.dto.SummaryHistoryListRequest;
import com.readum.presentation.controller.summary.dto.SummaryHistoryListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "감상 기록", description = "사용자 본인의 완성된 감상문(종료된 세션) 목록 조회")
@RestController
@RequestMapping("/api/v1/summaries")
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryHistorySearchService summaryHistorySearchService;

    @Operation(
            summary = "내 감상 기록 목록 조회",
            description = "로그인한 사용자 본인의 감상 기록을 생성일 내림차순(최신순)으로 페이지네이션 조회한다. " +
                    "채팅 세션이 종료(CLOSED)되었고 감상문이 완성(COMPLETED)된 기록만 포함한다 — " +
                    "아직 대화 중인 세션에 자동 생성된 감상문은 제외된다. " +
                    "감상문 내용은 100자까지만 노출하고 초과분은 \"...\" 로 줄여 응답한다. " +
                    "한 번에 20개씩 조회하며, 기록이 없으면 빈 배열로 200 응답한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "page 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping
    public ResponseEntity<GlobalApiResponse<SummaryHistoryListResponse>> getMyHistory(
            @CookieValue(name = "user_session") String userSessionId,
            @Valid @ModelAttribute SummaryHistoryListRequest request
    ) {
        SummaryHistoryListResult result = summaryHistorySearchService.findMyHistory(request.toCommand(userSessionId));
        return GlobalApiResponse.ok(SummaryHistoryListResponse.from(result));
    }
}
