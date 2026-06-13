package com.readum.presentation.controller.summary;

import com.readum.domain.summary.dto.SummaryHistoryItemResult;
import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.domain.summary.service.SummaryHistorySearchService;
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
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SummaryControllerTest {

    private static final String USER_SESSION_ID = "test-session-id";
    private static final Cookie USER_SESSION_COOKIE = new Cookie("user_session", USER_SESSION_ID);

    @Mock
    private SummaryHistorySearchService summaryHistorySearchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SummaryController controller = new SummaryController(summaryHistorySearchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

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
}
