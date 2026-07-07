package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.AiChatChunk;
import com.readum.domain.aiChat.dto.AssembledContext;
import com.readum.domain.aiChat.dto.HistoryMessage;
import com.readum.domain.aiChat.event.FirstAssistantResponseCompletedEvent;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.TokenCounter;
import com.readum.domain.exception.BadRequestException;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.entity.AiChatMessage;
import com.readum.model.aiChat.entity.AiChatMessageFixture;
import com.readum.model.aiChat.entity.AiChatSession;
import com.readum.model.aiChat.entity.AiChatSessionFixture;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
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
    private SummaryJobRepository summaryJobRepository;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AiChatMessagePersistService persistService;

    @Test
    void loadHistory_소유권_없는_세션이면_NotFoundException_을_던진다() {
        Long userId = 1L;
        Long sessionId = 7L;

        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> persistService.loadHistory(sessionId, userId))
                .asInstanceOf(InstanceOfAssertFactories.type(NotFoundException.class))
                .extracting(NotFoundException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_NOT_FOUND);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void loadHistory_종료된_세션이면_BadRequest_SESSION_ALREADY_SUMMARIZED_를_던진다() {
        // 감상문이 완성되어 영구 종료(LOCKED)된 세션에는 메시지 전송이 차단되는 계약.
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession locked = AiChatSessionFixture.persistedSummarizedSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(locked));

        assertThatThrownBy(() -> persistService.loadHistory(sessionId, userId))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_ALREADY_SUMMARIZED);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void loadHistory_활성_세션이지만_차단_작업이_있으면_BadRequest_SESSION_LOCKED_를_던진다() {
        // 감상문을 생성 중인 세션 — 일시적으로 메시지 전송 불가.
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(active));
        given(summaryJobRepository.existsBlockingSummaryJob(eq(sessionId), any(LocalDateTime.class)))
                .willReturn(true);

        assertThatThrownBy(() -> persistService.loadHistory(sessionId, userId))
                .asInstanceOf(InstanceOfAssertFactories.type(BadRequestException.class))
                .extracting(BadRequestException::getErrorCode)
                .isEqualTo(AiChatErrorCode.SESSION_LOCKED);

        verify(aiChatMessageRepository, never()).save(any());
    }

    @Test
    void loadHistory_는_이전_이력만_조회하고_USER_메시지를_저장하지_않는다() {
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(active));
        given(summaryJobRepository.existsBlockingSummaryJob(eq(sessionId), any(LocalDateTime.class)))
                .willReturn(false);
        given(aiChatHistorySearchService.assembleContext(sessionId)).willReturn(new AssembledContext(null, List.of(
                new HistoryMessage(HistoryMessage.Role.USER, "이전 질문"),
                new HistoryMessage(HistoryMessage.Role.ASSISTANT, "이전 응답")
        )));

        AiChatMessagePersistService.MessageLoadResult result =
                persistService.loadHistory(sessionId, userId);

        assertThat(result.notSummarizedChatRaws()).hasSize(2);
        assertThat(result.notSummarizedChatRaws().get(0).content()).isEqualTo("이전 질문");
        assertThat(result.notSummarizedChatRaws().get(1).content()).isEqualTo("이전 응답");
        assertThat(result.userBookId()).isEqualTo(100L);

        // 검증/조회만 — USER 미저장, 턴 카운트 미증가
        verify(aiChatMessageRepository, never()).save(any());
        assertThat(active.getUserMessageCount()).isZero();
    }

    @Test
    void recordUserMessage_는_COMPLETED_저장과_세션_턴카운트_증가를_수행한다() {
        Long userId = 1L;
        Long sessionId = 7L;
        AiChatSession active = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 0, 0, null
        );
        given(aiChatSessionRepository.findByIdAndOwner(sessionId, userId)).willReturn(Optional.of(active));
        given(aiChatMessageRepository.save(any(AiChatMessage.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        persistService.recordUserMessage(sessionId, userId, "이번 질문");

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository).save(captor.capture());
        AiChatMessage saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(AiChatMessage.Role.USER);
        assertThat(saved.getStatus()).isEqualTo(AiChatMessage.Status.COMPLETED);
        assertThat(saved.getContent()).isEqualTo("이번 질문");
        assertThat(active.getUserMessageCount()).isEqualTo(1);
    }

    @Test
    void recordRejectedUserMessage_는_REJECTED_저장만_하고_턴카운트를_증가시키지_않는다() {
        Long sessionId = 7L;
        given(aiChatMessageRepository.save(any(AiChatMessage.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        persistService.recordRejectedUserMessage(sessionId, "차단된 질문");

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(aiChatMessageRepository).save(captor.capture());
        AiChatMessage saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(AiChatMessage.Role.USER);
        assertThat(saved.getStatus()).isEqualTo(AiChatMessage.Status.REJECTED);
        assertThat(saved.getContent()).isEqualTo("차단된 질문");
        // 거부 메시지는 세션 검증/턴 카운트와 무관 — 세션 조회 자체를 하지 않는다.
        verify(aiChatSessionRepository, never()).findByIdAndOwner(any(), any());
    }

    @Test
    void 첫_ASSISTANT_응답_완료_시_유저_첫_질문을_담은_제목_생성_이벤트가_발행된다() {
        Long sessionId = 7L;
        AiChatSession firstExchangeSession = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 1, 0, null
        );
        AiChatMessage firstUserMessage = AiChatMessage.createUserMessage(sessionId, "첫 질문");
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(firstExchangeSession));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(aiChatMessageRepository.findFirstUserMessage(sessionId)).willReturn(Optional.of(firstUserMessage));

        AiChatChunk.Completion meta = new AiChatChunk.Completion(100, 50, 150, null);

        persistService.saveAssistantSuccess(sessionId, "첫 응답", meta);

        // saveAssistantSuccess 는 제목 생성 이벤트 외에 컨텍스트 요약 트리거 이벤트도 발행하므로, 모든 발행을 캡처해 걸러낸다.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        FirstAssistantResponseCompletedEvent event = captor.getAllValues().stream()
                .filter(FirstAssistantResponseCompletedEvent.class::isInstance)
                .map(FirstAssistantResponseCompletedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.firstUserMessage())
                .as("제목 생성 입력은 유저의 첫 질문이어야 한다")
                .isEqualTo(firstUserMessage);
    }

    @Test
    void 두번째_이후_ASSISTANT_응답이면_제목_생성_이벤트가_발행되지_않는다() {
        Long sessionId = 7L;
        AiChatSession laterSession = AiChatSessionFixture.persistedActiveSession(
                sessionId, 100L, 3, 100, "이미 있는 제목"
        );
        given(aiChatSessionRepository.findById(sessionId)).willReturn(Optional.of(laterSession));
        given(aiChatMessageRepository.save(any(AiChatMessage.class))).willAnswer(invocation -> invocation.getArgument(0));

        AiChatChunk.Completion meta = new AiChatChunk.Completion(100, 50, 150, null);

        persistService.saveAssistantSuccess(sessionId, "후속 응답", meta);

        verify(eventPublisher, never()).publishEvent(any(FirstAssistantResponseCompletedEvent.class));
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
