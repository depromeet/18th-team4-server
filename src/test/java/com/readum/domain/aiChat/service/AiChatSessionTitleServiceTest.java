package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatSessionRepository;

import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatSessionTitleServiceTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatTitleClient aiChatTitleClient;

    private AiChatSessionTitleService titleService;

    @BeforeEach
    void setUp() {
        titleService = new AiChatSessionTitleService(
                aiChatSessionRepository,
                aiChatTitleClient,
                new NoopTransactionManager()
        );
    }

    @Test
    void 세션이_존재하고_LLM_이_제목을_반환하면_세션_title_이_갱신된다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                1, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        List<AiChatMessage> messages = List.of(
                AiChatMessage.of(1L, sessionId, AiChatMessage.Role.USER, "작가의 의도가 뭐야",
                        null, null, null, null, AiChatMessage.Status.COMPLETED, LocalDateTime.now()),
                AiChatMessage.of(2L, sessionId, AiChatMessage.Role.ASSISTANT, "작가는 ...",
                        null, null, null, null, AiChatMessage.Status.COMPLETED, LocalDateTime.now())
        );
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(true);
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatTitleClient.generate(messages)).willReturn("작가의 의도 분석");

        titleService.execute(new GenerateSessionTitleCommand(sessionId, messages));

        assertThat(session.getTitle()).isEqualTo("작가의 의도 분석");
    }

    @Test
    void 세션이_없으면_NotFoundException_을_던지고_LLM_은_호출되지_않는다() {
        Long sessionId = 7L;
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(false);

        assertThatThrownBy(() -> titleService.execute(new GenerateSessionTitleCommand(sessionId, List.of())))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiChatTitleClient, never()).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void LLM_이_빈_제목을_반환하면_기존_title_은_변경되지_않는다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                1, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        List<AiChatMessage> messages = List.of();
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(true);
        given(aiChatTitleClient.generate(messages)).willReturn("   ");

        titleService.execute(new GenerateSessionTitleCommand(sessionId, messages));

        assertThat(session.getTitle()).isNull();
    }

    @Test
    void LLM_이_길게_제목을_반환해도_엔티티가_컬럼_길이까지_자른다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                1, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        List<AiChatMessage> messages = List.of();
        given(aiChatSessionRepository.existsById(sessionId)).willReturn(true);
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        String longTitle = "가".repeat(150);
        given(aiChatTitleClient.generate(messages)).willReturn(longTitle);

        titleService.execute(new GenerateSessionTitleCommand(sessionId, messages));

        assertThat(session.getTitle()).hasSize(100);
    }

    /**
     * TransactionTemplate 가 콜백을 그대로 실행하도록 한 테스트 전용 noop 매니저.
     */
    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
