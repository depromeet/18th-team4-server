package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.SummaryEditCommand;
import com.readum.domain.summary.dto.SummaryResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.entity.Summary;
import com.readum.model.summary.entity.SummaryFixture;
import com.readum.model.summary.repository.SummaryRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SummaryEditServiceTest {

    private static final Long SESSION_ID = 1L;
    private static final Long SUMMARY_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final String USER_SESSION_ID = "test-session-id";
    private static final Long USER_BOOK_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private SummaryRepository summaryRepository;

    @InjectMocks
    private SummaryEditService summaryEditService;

    @BeforeEach
    void setUp() {
        User testUser = UserFixture.persistedUser(USER_ID, USER_SESSION_ID);
        lenient().when(userRepository.findBySessionId(USER_SESSION_ID)).thenReturn(Optional.of(testUser));
    }

    @Test
    void 최신_감상문을_정상적으로_수정한다() {
        AiChatSession session = ownedSession();
        Summary summary = SummaryFixture.persistedSummary(
                SUMMARY_ID, USER_BOOK_ID, SESSION_ID, "기존 제목", "기존 본문"
        );
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(session));
        given(summaryRepository.findByAiChatSessionId(SESSION_ID))
                .willReturn(Optional.of(summary));

        SummaryEditCommand command = new SummaryEditCommand(USER_SESSION_ID, SESSION_ID, "수정된 제목", "수정된 본문");
        SummaryResult result = summaryEditService.execute(command);

        assertThat(result.title()).isEqualTo("수정된 제목");
        assertThat(result.body()).isEqualTo("수정된 본문");
        assertThat(summary.getTitle()).isEqualTo("수정된 제목");
        assertThat(summary.getBody()).isEqualTo("수정된 본문");
    }

    @Test
    void 유효하지_않은_세션이면_UnauthorizedException이_발생한다() {
        given(userRepository.findBySessionId("invalid")).willReturn(Optional.empty());

        SummaryEditCommand command = new SummaryEditCommand("invalid", SESSION_ID, "제목", "본문");

        assertThatThrownBy(() -> summaryEditService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(UnauthorizedException.class))
                .extracting(UnauthorizedException::getErrorCode)
                .isEqualTo(UserErrorCode.INVALID_SESSION);
    }

    @Test
    void 소유하지_않은_세션이면_NotFoundException이_발생한다() {
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.empty());

        SummaryEditCommand command = new SummaryEditCommand(USER_SESSION_ID, SESSION_ID, "제목", "본문");

        assertThatThrownBy(() -> summaryEditService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void 감상문이_없으면_NotFoundException이_발생한다() {
        AiChatSession session = ownedSession();
        given(aiChatSessionRepository.findByIdAndOwner(SESSION_ID, USER_ID)).willReturn(Optional.of(session));
        given(summaryRepository.findByAiChatSessionId(SESSION_ID))
                .willReturn(Optional.empty());

        SummaryEditCommand command = new SummaryEditCommand(USER_SESSION_ID, SESSION_ID, "제목", "본문");

        assertThatThrownBy(() -> summaryEditService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SUMMARY_NOT_FOUND);
    }

    private AiChatSession ownedSession() {
        return AiChatSessionFixture.persistedActiveSession(
                SESSION_ID, USER_BOOK_ID, 10, 600, "마지막 메시지"
        );
    }
}
