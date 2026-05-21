package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private AiChatHistorySearchService aiChatHistorySearchService;

    @Mock
    private AiChatSessionTitleService aiChatSessionTitleService;

    @InjectMocks
    private AiChatMessagePersistService persistService;

    @AfterEach
    void cleanupSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

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
        AiChatSession closed = AiChatSessionFixture.persistedClosedSession(
                sessionId, 100L, 0, 0, null
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
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(active));
        given(aiChatHistorySearchService.findPreviousHistory(sessionId)).willReturn(List.of(
                new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
        ));

        AiChatMessagePersistService.MessageLoadResult result =
                persistService.loadHistoryAndRecordUserMessage(sessionId, userId, "이번 질문");

        // 결과는 이전 이력만 (현재 메시지는 SendService 가 직접 append)
        assertThat(result.history()).hasSize(2);
        assertThat(result.history().get(0).content()).isEqualTo("이전 질문");
        assertThat(result.history().get(1).content()).isEqualTo("이전 응답");
        assertThat(result.userBookId()).isEqualTo(100L);

        // history 조회 후 USER 메시지 저장 — 순서 검증 (Hibernate auto-flush 회피)
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(aiChatHistorySearchService, aiChatMessageRepository);
        inOrder.verify(aiChatHistorySearchService).findPreviousHistory(sessionId);
        inOrder.verify(aiChatMessageRepository).save(any(AiChatMessage.class));

        // 세션 통계 갱신 확인
        assertThat(active.getUserMessageCount()).isEqualTo(1);
    }

    @Test
    void 첫_ASSISTANT_응답_완료_시_제목_생성_트리거가_afterCommit_로_등록된다() {
        Long sessionId = 7L;
        AiChatSession firstExchangeSession = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(firstExchangeSession));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(aiChatMessageRepository.findValidMessagesBySessionIdOrderByCreatedAtAsc(sessionId))
                .willReturn(List.of());

        AiChatChunk.Completion meta = new AiChatChunk.Completion(100, 50, 150, null);

        TransactionSynchronizationManager.initSynchronization();
        try {
            persistService.saveAssistantSuccess(sessionId, "첫 응답", meta);

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations)
                    .as("첫 교환 완료 후 제목 생성을 트리거할 synchronization 이 등록되어야 한다")
                    .hasSize(1);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void 두번째_이후_ASSISTANT_응답이면_제목_생성_트리거가_등록되지_않는다() {
        Long sessionId = 7L;
        AiChatSession laterSession = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 3, 100, "이미 있는 제목"
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(laterSession));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));

        AiChatChunk.Completion meta = new AiChatChunk.Completion(100, 50, 150, null);

        TransactionSynchronizationManager.initSynchronization();
        try {
            persistService.saveAssistantSuccess(sessionId, "후속 응답", meta);

            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .as("첫 교환이 아닌 세션은 제목 생성 트리거를 등록하지 않아야 한다")
                    .isEmpty();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void saveAssistantSuccess_은_ASSISTANT_COMPLETED_저장과_세션_토큰_누적을_수행한다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, "이전 미리보기"
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessageFixture.persistedCopyOf(99L, incoming);
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
        // 세션 누적치는 outputTokens(58) 만 합산. totalTokens(370) 는 입력 프롬프트까지 포함해 중복 집계 사유.
        assertThat(session.getAccumulatedTokens()).isEqualTo(58);
    }

    @Test
    void saveAssistantFailed_은_FAILED_저장만_수행하고_partial_이_null_이면_빈_문자열로_저장() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));

        // 입력 10, 출력 4 만 받고 끊긴 케이스. 세션 누적은 outputTokens(4) 만 반영되어야 한다.
        AiChatChunk.Completion meta = new AiChatChunk.Completion(10, 4, 14, null);
        persistService.saveAssistantFailed(sessionId, null, meta);

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository).save(captor.capture());
        AiChatMessage inserted = captor.getValue();
        assertThat(inserted.getRole()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(inserted.getStatus()).isEqualTo(AiChatMessage.Status.FAILED);
        assertThat(inserted.getContent()).isEqualTo("");
        assertThat(session.getAccumulatedTokens()).isEqualTo(4);
    }

    @Test
    void saveAssistant_시_meta_가_null_이면_세션_토큰은_누적되지_않는다() {
        Long sessionId = 7L;
        AiChatSession session = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 2, 100, "기존 제목"
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> {
            AiChatMessage incoming = invocation.getArgument(0);
            return AiChatMessageFixture.persistedCopyOf(1L, incoming);
        });

        persistService.saveAssistantSuccess(sessionId, "본문", null);

        assertThat(session.getAccumulatedTokens()).isEqualTo(100);
    }
}
