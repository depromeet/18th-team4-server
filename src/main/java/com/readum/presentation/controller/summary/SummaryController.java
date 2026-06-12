package com.readum.presentation.controller.summary;

import com.readum.domain.summary.dto.MonthlySummaryResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.service.SummarySearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.summary.dto.MonthlySummariesResponse;
import com.readum.presentation.controller.summary.dto.SummaryDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@Tag(name = "감상 기록", description = "홈 캘린더에서 보는 날짜별 감상 기록(완성된 감상문) 조회")
@RestController
@RequestMapping("/api/v1/summaries")
@RequiredArgsConstructor
public class SummaryController {

    private final SummarySearchService summarySearchService;

    @Operation(
            summary = "월별 감상 기록 조회",
            description = """
                    한 달치 완성된 감상문을 평탄한 리스트로 반환한다.
                    최신순 정렬 — summaryDate 내림차순, 같은 날짜 안에서는 생성 시각 내림차순.
                    날짜별 그룹핑(캘린더 점 찍기)은 클라이언트가 수행한다.
                    기록이 없는 달은 빈 배열을 반환한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (기록이 없으면 빈 배열)"),
            @ApiResponse(responseCode = "400", description = "yearMonth 형식 오류 또는 누락 (YYYY-MM)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping
    public ResponseEntity<GlobalApiResponse<MonthlySummariesResponse>> getMonthlySummaries(
            @CookieValue(name = "user_session") String userSessionId,
            @RequestParam YearMonth yearMonth
    ) {
        List<MonthlySummaryResult> results = summarySearchService.findMonthly(yearMonth, userSessionId);
        return GlobalApiResponse.ok(MonthlySummariesResponse.from(results));
    }

    @Operation(
            summary = "감상 기록 상세 조회",
            description = """
                    감상문 id 로 제목과 본문을 조회한다.
                    존재하지 않거나, 본인 소유가 아니거나, 완성(COMPLETED)되지 않은 감상문은 모두 404 로 응답한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "감상문 없음, 소유권 없음, 또는 미완성")
    })
    @GetMapping("/{summaryId}")
    public ResponseEntity<GlobalApiResponse<SummaryDetailWrapper>> getSummaryDetail(
            @CookieValue(name = "user_session") String userSessionId,
            @PathVariable Long summaryId
    ) {
        SummaryResult result = summarySearchService.findById(summaryId, userSessionId);
        return GlobalApiResponse.ok(new SummaryDetailWrapper(SummaryDetailResponse.from(result)));
    }

    /** 응답 포맷 컨벤션(단수는 {@code 단수명사: {}})에 맞춰 detail 을 {@code summary} 키로 감싼다. */
    public record SummaryDetailWrapper(SummaryDetailResponse summary) {}
}
