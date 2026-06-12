package com.readum.presentation.controller.summary;

import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.MonthlySummaryResult;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.summary.exception.SummaryErrorCode;
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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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
    private SummarySearchService summarySearchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SummaryController(summarySearchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 월별_조회는_감상_기록_리스트를_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willReturn(List.of(new MonthlySummaryResult(
                        17L, LocalDate.of(2026, 6, 11), "감상문 제목", "감상문 본문", "책 이름")));

        mockMvc.perform(get("/api/v1/summaries")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries[0].summaryId").value(17))
                .andExpect(jsonPath("$.data.summaries[0].summaryDate").value("2026-06-11"))
                .andExpect(jsonPath("$.data.summaries[0].title").value("감상문 제목"))
                .andExpect(jsonPath("$.data.summaries[0].body").value("감상문 본문"))
                .andExpect(jsonPath("$.data.summaries[0].bookTitle").value("책 이름"));
    }

    @Test
    void 월별_조회는_기록이_없으면_빈_배열을_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/summaries")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries").isArray())
                .andExpect(jsonPath("$.data.summaries").isEmpty());
    }

    @Test
    void yearMonth_형식이_틀리면_400_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/summaries")
                        .param("yearMonth", "2026-13")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void yearMonth_가_누락되면_400_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/summaries")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("필수 요청 값이 없습니다: yearMonth"));
    }

    @Test
    void 월별_조회는_세션이_무효하면_401_을_반환한다() throws Exception {
        given(summarySearchService.findMonthly(eq(YearMonth.of(2026, 6)), anyString()))
                .willThrow(new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        mockMvc.perform(get("/api/v1/summaries")
                        .param("yearMonth", "2026-06")
                        .cookie(USER_SESSION_COOKIE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void 상세_조회는_채팅_세션_id와_감상문_제목과_본문을_반환한다() throws Exception {
        given(summarySearchService.findById(eq(17L), anyString()))
                .willReturn(new SummaryResult(1000L, "감상문 제목", "감상문 본문", null));

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
