package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatSessionCreateCommand;
import com.readum.domain.aiChat.dto.AiChatSessionCreateResult;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.book.entity.UserBook;
import com.readum.model.book.repository.UserBookRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatSessionCreateServiceTest {

    @Mock
    private UserBookRepository userBookRepository;

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @InjectMocks
    private AiChatSessionCreateService aiChatSessionCreateService;

    @Test
    void 본인_소유_userBook_으로_세션_생성_성공() {
        Long userId = 1L;
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(userId, userBookId);

        UserBook userBook = UserBook.of(userBookId, userId, 100L, LocalDateTime.now());
        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.of(userBook));

        LocalDateTime now = LocalDateTime.now();
        AiChatSession persisted = AiChatSession.of(
                42L, userBookId, AiChatSession.Status.ACTIVE, 0, 0, null, now, now
        );
        given(aiChatSessionRepository.save(any(AiChatSession.class))).willReturn(persisted);

        AiChatSessionCreateResult result = aiChatSessionCreateService.execute(command);

        assertThat(result.id()).isEqualTo(42L);
    }

    @Test
    void 다른_사용자_소유_userBook_은_NotFoundException_을_던지고_save_는_호출되지_않는다() {
        Long userId = 1L;
        Long userBookId = 10L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(userId, userBookId);

        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);

        verify(aiChatSessionRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_userBook_도_동일한_USER_BOOK_NOT_FOUND_예외() {
        // 존재하지 않는 케이스와 권한 없는 케이스가 정보 누설 없이 동일하게 처리되는지 검증
        Long userId = 1L;
        Long userBookId = 999_999L;
        AiChatSessionCreateCommand command = new AiChatSessionCreateCommand(userId, userBookId);

        given(userBookRepository.findByIdAndUserId(userBookId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> aiChatSessionCreateService.execute(command))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.USER_BOOK_NOT_FOUND);
    }
}
