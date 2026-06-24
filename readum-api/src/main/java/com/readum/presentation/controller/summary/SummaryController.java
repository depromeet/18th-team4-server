package com.readum.presentation.controller.summary;

import com.readum.domain.summary.dto.MonthlyReadingRecordResult;
import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.service.SummaryHistorySearchService;
import com.readum.domain.summary.service.SummarySearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.summary.dto.MonthlyReadingRecordsResponse;
import com.readum.presentation.controller.summary.dto.SummaryDetailResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@Tag(name = "감상 기록", description = "감상문 전체 목록(무한스크롤)·단건 상세, 홈 캘린더의 월별 독서 기록(채팅 세션) 조회")
@RestController
@RequestMapping("/api/v1/summaries")
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryHistorySearchService summaryHistorySearchService;
    private final SummarySearchService summarySearchService;

    @Operation(
            summary = "내 감상 기록 목록 조회",
            description = "로그인한 사용자 본인의 감상 기록을 생성일 내림차순(최신순)으로 페이지네이션 조회한다. " +
                    "채팅 세션이 종료(CLOSED)되었고 감상문이 완성(COMPLETED)된 기록만 포함한다 — " +
                    "아직 대화 중인 세션에 자동 생성된 감상문은 제외된다. " +
                    "각 항목은 상세 이동용 감상문 id(summaryId), 책 제목(bookTitle), 세션 제목(sessionTitle), 생성일(createdAt)을 담는다. " +
                    "summaryId 는 항상 존재하며, sessionTitle 은 비동기 제목 생성이 실패한 드문 경우에만 null 이다. " +
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

    @Operation(
            summary = "월별 독서 기록 조회 (홈 캘린더)",
            description = """
                    한 달치 독서 기록(채팅 세션)을 평탄한 리스트로 반환한다.
                    감상문 생성 여부와 무관하게 포함하며, 감상문이 없는 기록은 summaryId 가 null 이다.
                    조회·정렬 기준은 마지막 채팅 시각(lastChattedAt) 내림차순 — 같은 시각이면 chatSessionId 내림차순.
                    채팅이 한 번도 없는 세션은 포함되지 않는다. 날짜별 그룹핑은 클라이언트가 수행한다.
                    기록이 없는 달은 빈 배열을 반환한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (기록이 없으면 빈 배열)"),
            @ApiResponse(responseCode = "400", description = "yearMonth 형식 오류 또는 누락 (YYYY-MM)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping("/calendar")
    public ResponseEntity<GlobalApiResponse<MonthlyReadingRecordsResponse>> getMonthlyReadingRecords(
            @CookieValue(name = "user_session") String userSessionId,
            @RequestParam YearMonth yearMonth
    ) {
        List<MonthlyReadingRecordResult> results = summarySearchService.findMonthly(yearMonth, userSessionId);
        return GlobalApiResponse.ok(MonthlyReadingRecordsResponse.from(results));
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
            @ApiResponse(responseCode = "400", description = "summaryId 타입 오류 (숫자가 아님)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "감상문 없음, 소유권 없음, 또는 미완성")
    })
    @GetMapping("/{summaryId}")
    public ResponseEntity<GlobalApiResponse<SummaryDetailResponse>> getSummaryDetail(
            @CookieValue(name = "user_session") String userSessionId,
            @PathVariable Long summaryId
    ) {
        SummaryResult result = summarySearchService.findById(summaryId, userSessionId);
        return GlobalApiResponse.ok(SummaryDetailResponse.from(result));
    }
}
