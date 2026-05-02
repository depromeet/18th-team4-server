package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.history.ChatHistoryBuilder;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiChatMessagePersistServiceTest {

    @Mock
    private AiChatSessionRepository aiChatSessionRepository;

    @Mock
    private AiChatMessageRepository aiChatMessageRepository;

    @Mock
    private ChatHistoryBuilder chatHistoryBuilder;

    @InjectMocks
    private AiChatMessagePersistService persistService;

    @Test
    void 소유권_없는_세션이면_NotFoundException_을_던지고_USER_메시지는_저장되지_않는다() {
        Long userId = 1L;
        Long sessionId = 7L;

        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> persistService.loadHistoryAndRecordUserMessage(sessionId, userId, "질문"))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void 종료된_세션이면_BadRequest_SESSION_CLOSED_를_던진다() {
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession closed = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.CLOSED,
                0, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(closed));

        assertThatThrownBy(() -> persistService.loadHistoryAndRecordUserMessage(sessionId, userId, "질문"))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_CLOSED);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void 정상_세션이면_history_조회와_USER_메시지_저장이_순서대로_실행된다() {
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession active = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                0, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(active));
        given(chatHistoryBuilder.buildPreviousHistory(sessionId)).willReturn(List.of(
                new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
        ));

        List<HistoryMessage> result = persistService.loadHistoryAndRecordUserMessage(sessionId, userId, "이번 질문");

        // 결과는 이전 이력만 (현재 메시지는 SendService 가 직접 append)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("이전 질문");
        assertThat(result.get(1).content()).isEqualTo("이전 응답");

        // history 조회 후 USER 메시지 저장 — 순서 검증 (Hibernate auto-flush 회피)
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(chatHistoryBuilder, aiChatMessageRepository);
        inOrder.verify(chatHistoryBuilder).buildPreviousHistory(sessionId);
        inOrder.verify(aiChatMessageRepository).save(any(AiChatMessage.class));

        // 세션 통계 갱신 확인
        assertThat(active.getUserMessageCount()).isEqualTo(1);
        assertThat(active.getLastMessagePreview()).isEqualTo("이번 질문");
    }

    @Test
    void saveAssistantSuccess_은_ASSISTANT_COMPLETED_저장과_세션_토큰_누적을_수행한다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                1, 0, "이전 미리보기", LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    99L,
                    incoming.getSessionId(),
                    incoming.getRole(),
                    incoming.getContent(),
                    incoming.getQuoteText(),
                    incoming.getInputTokens(),
                    incoming.getOutputTokens(),
                    incoming.getTotalTokens(),
                    incoming.getStatus(),
                    incoming.getCreatedAt()
            );
        });

        AiChatChunk.Completion meta = new AiChatChunk.Completion(312, 58, 370, null);
        AiChatMessage saved = persistService.saveAssistantSuccess(sessionId, "응답 본문", meta);

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository).save(captor.capture());
        AiChatMessage inserted = captor.getValue();
        assertThat(inserted.getRole()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(inserted.getStatus()).isEqualTo(AiChatMessage.Status.COMPLETED);
        assertThat(inserted.getContent()).isEqualTo("응답 본문");
        assertThat(inserted.getTotalTokens()).isEqualTo(370);

        assertThat(saved.getId()).isEqualTo(99L);
        assertThat(session.getAccumulatedTokens()).isEqualTo(370);
    }

    @Test
    void saveAssistantFailed_은_FAILED_저장만_수행하고_partial_이_null_이면_빈_문자열로_저장() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSession.of(
                sessionId, 100L, AiChatSession.Status.ACTIVE,
                1, 0, null, LocalDateTime.now(), LocalDateTime.now()
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));

        AiChatChunk.Completion meta = new AiChatChunk.Completion(10, 0, 10, null);
        persistService.saveAssistantFailed(sessionId, null, meta);

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository).save(captor.capture());
        AiChatMessage inserted = captor.getValue();
        assertThat(inserted.getRole()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(inserted.getStatus()).isEqualTo(AiChatMessage.Status.FAILED);
        assertThat(inserted.getContent()).isEqualTo("");
        assertThat(session.getAccumulatedTokens()).isEqualTo(10);
    }

    @Test
    void saveAssistant_시_meta_가_null_이면_세션_토큰은_누적되지_않는다() {
        Long sessionId = 7L;
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessage.of(
                    1L, incoming.getSessionId(), incoming.getRole(), incoming.getContent(),
                    incoming.getQuoteText(), incoming.getInputTokens(), incoming.getOutputTokens(),
                    incoming.getTotalTokens(), incoming.getStatus(), incoming.getCreatedAt()
            );
        });

        persistService.saveAssistantSuccess(sessionId, "본문", null);

        verify(aiChatSessionRepository, never()).findById(sessionId);
    }
}
