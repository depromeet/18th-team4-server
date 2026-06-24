package com.readum.domain.summary.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.summary.dto.SummaryHistoryItemResult;
import com.readum.domain.summary.dto.SummaryHistoryListCommand;
import com.readum.domain.summary.dto.SummaryHistoryListResult;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.summary.repository.projection.SummaryHistoryProjection;
import com.readum.model.user.entity.User;
import com.readum.model.user.entity.UserFixture;
import com.readum.model.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SummaryHistorySearchServiceTest {

    private static final String USER_SESSION_ID = "test-session-id";
    private static final Long USER_ID = 1L;
    private static final int PAGE_SIZE = 20;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @InjectMocks
    private SummaryHistorySearchService summaryHistorySearchService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 유효하지_않은_user_session_쿠키면_UnauthorizedException() {
        given(userRepository.findBySessionId("invalid")).willReturn(Optional.empty());

        assertThatThrownBy(() -> summaryHistorySearchService.findMyHistory(
                new SummaryHistoryListCommand("invalid", 1)
        ))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 감상_기록이_없으면_빈_배열과_hasNext_false_를_반환한다() {
        given(summaryRepository.findLatestHistoryByUserId(USER_ID, PageRequest.of(0, PAGE_SIZE)))
                .willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, PAGE_SIZE), false));

        SummaryHistoryListResult result = summaryHistorySearchService.findMyHistory(
                new SummaryHistoryListCommand(USER_SESSION_ID, 1)
        );

        assertThat(result.summaries()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(PAGE_SIZE);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void projection_이_응답용_Result_로_변환된다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 7, 9, 0, 0);
        given(summaryRepository.findLatestHistoryByUserId(USER_ID, PageRequest.of(0, PAGE_SIZE)))
                .willReturn(new SliceImpl<>(List.of(
                        new SummaryHistoryProjection(101L, "데미안", "데미안을 읽고 나서", createdAt)
                ), PageRequest.of(0, PAGE_SIZE), false));

        SummaryHistoryListResult result = summaryHistorySearchService.findMyHistory(
                new SummaryHistoryListCommand(USER_SESSION_ID, 1)
        );

        assertThat(result.summaries()).hasSize(1);
        SummaryHistoryItemResult item = result.summaries().get(0);
        assertThat(item.summaryId()).isEqualTo(101L);
        assertThat(item.bookTitle()).isEqualTo("데미안");
        assertThat(item.sessionTitle()).isEqualTo("데미안을 읽고 나서");
        assertThat(item.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void page_파라미터는_1_indexed_에서_0_indexed_로_변환되고_size_는_20_고정이다() {
        given(summaryRepository.findLatestHistoryByUserId(USER_ID, PageRequest.of(2, PAGE_SIZE)))
                .willReturn(new SliceImpl<>(List.of(), PageRequest.of(2, PAGE_SIZE), false));

        SummaryHistoryListResult result = summaryHistorySearchService.findMyHistory(
                new SummaryHistoryListCommand(USER_SESSION_ID, 3)
        );

        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(PAGE_SIZE);
    }

    @Test
    void 다음_페이지가_있으면_hasNext_true_를_반환한다() {
        given(summaryRepository.findLatestHistoryByUserId(USER_ID, PageRequest.of(0, PAGE_SIZE)))
                .willReturn(new SliceImpl<>(List.of(
                        new SummaryHistoryProjection(1L, "책", "세션 제목", LocalDateTime.now())
                ), PageRequest.of(0, PAGE_SIZE), true));

        SummaryHistoryListResult result = summaryHistorySearchService.findMyHistory(
                new SummaryHistoryListCommand(USER_SESSION_ID, 1)
        );

        assertThat(result.hasNext()).isTrue();
    }
}
