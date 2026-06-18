package com.readum.presentation.controller.summary;

import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlyReadingRecordResult;
import com.readum.domain.summary.dto.SummaryHistoryItemResult;
import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.exception.SummaryErrorCode;
import com.readum.domain.summary.service.SummaryHistorySearchService;
import com.readum.domain.summary.service.SummarySearchService;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.presentation.common.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SummaryControllerTest {

    private static final Cookie USER_SESSION_COOKIE = new Cookie("user_session", "test-session-id");

    @Mock
    private SummaryHistorySearchService summaryHistorySearchService;

    @Mock
    private SummarySearchService summarySearchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SummaryController controller = new SummaryController(summaryHistorySearchService, summarySearchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ===== 전체 감상 기록 목록 (GET /api/v1/summaries) =====

    @Test
    void 감상_기록_목록_조회_정상_응답() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 7, 9, 0, 0);
        given(summaryHistorySearchService.findMyHistory(any()))
                .willReturn(new SummaryHistoryListResult(
                        List.of(
                                new SummaryHistoryItemResult("데미안", "깊은 울림을 주는 책이었다.", createdAt),
                                new SummaryHistoryItemResult("1984", "감시 사회의 공포.", createdAt.minusDays(1))
                        ),
                        1,
                        20,
                        false
                ));

        mockMvc.perform(get("/api/v1/summaries")
                        .cookie(USER_SESSION_COOKIE)
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries.length()").value(2))
                .andExpect(jsonPath("$.data.summaries[0].bookTitle").value("데미안"))
                .andExpect(jsonPath("$.data.summaries[0].content").value("깊은 울림을 주는 책이었다."))
                .andExpect(jsonPath("$.data.summaries[1].bookTitle").value("1984"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 감상_기록이_없으면_빈_배열을_반환한다() throws Exception {
        given(summaryHistorySearchService.findMyHistory(any()))
                .willReturn(new SummaryHistoryListResult(List.of(), 1, 20, false));

        mockMvc.perform(get("/api/v1/summaries")
                        .cookie(USER_SESSION_COOKIE)
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 감상_기록_조회_page_가_0이면_400() throws Exception {
        mockMvc.perform(get("/api/v1/summaries")
                        .cookie(USER_SESSION_COOKIE)
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("page는 1 이상")));
    }

    // ===== 월별 달력 조회 (GET /api/v1/summaries/calendar) =====

    @Test
    void 월별_조회는_독서_기록_리스트를_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willReturn(List.of(new MonthlyReadingRecordResult(
                        1000L, 17L, "책 이름", "채팅 제목", LocalDateTime.of(2026, 6, 11, 14, 32, 5))));

        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].chatSessionId").value(1000))
                .andExpect(jsonPath("$.data.records[0].summaryId").value(17))
                .andExpect(jsonPath("$.data.records[0].bookTitle").value("책 이름"))
                .andExpect(jsonPath("$.data.records[0].chatSummary").value("채팅 제목"))
                .andExpect(jsonPath("$.data.records[0].lastChattedAt").value("2026-06-11T14:32:05"));
    }

    @Test
    void 월별_조회는_감상문이_없는_기록의_summaryId_를_null_로_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willReturn(List.of(new MonthlyReadingRecordResult(
                        1001L, null, "다른 책", "또 다른 제목", LocalDateTime.of(2026, 6, 10, 9, 15, 40))));

        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].chatSessionId").value(1001))
                .andExpect(jsonPath("$.data.records[0].summaryId").value(nullValue()));
    }

    @Test
    void 월별_조회는_기록이_없으면_빈_배열을_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void yearMonth_형식이_틀리면_400_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .param("yearMonth", "2026-13")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void yearMonth_가_누락되면_400_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("필수 요청 값이 없습니다: yearMonth"));
    }

    @Test
    void 월별_조회는_세션이_무효하면_401_을_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willThrow(new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        mockMvc.perform(get("/api/v1/summaries/calendar")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").exists());
    }

    // ===== 감상 기록 상세 조회 (GET /api/v1/summaries/{summaryId}) =====

    @Test
    void 상세_조회는_채팅_세션_id와_감상문_제목과_본문을_반환한다() throws Exception {
        given(summarySearchService.findById(eq(17L), anyString()))
                .willReturn(new SummaryResult(1000L, "감상문 제목", "감상문 본문"));

        mockMvc.perform(get("/api/v1/summaries/17")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.aiChatSessionId").value(1000))
                .andExpect(jsonPath("$.data.summary.title").value("감상문 제목"))
                .andExpect(jsonPath("$.data.summary.body").value("감상문 본문"));
    }

    @Test
    void 상세_조회는_찾을_수_없으면_404_와_메시지를_반환한다() throws Exception {
        given(summarySearchService.findById(anyLong(), anyString()))
                .willThrow(new NotFoundException(SummaryErrorCode.SUMMARY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/summaries/99")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("감상문을 찾을 수 없습니다."));
    }
}
