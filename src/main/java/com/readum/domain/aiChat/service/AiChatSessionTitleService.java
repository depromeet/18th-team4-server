package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 채팅 세션의 제목을 생성·갱신하는 Command 서비스.
 * 트리거(첫 메시지 / 향후 N턴 재생성 / 수동 재명명 등) 와 무관하게 동일 진입점을 통해 동작한다.
 * 호출자가 첫 USER 메시지를 알고 있는 경우(첫 메시지 트리거) 그대로 전달하고,
 * 모르는 경우(재생성) 별도 로직으로 메시지를 가져와 command 에 담아 호출하면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatSessionTitleService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatTitleClient aiChatTitleClient;
    private final AiChatSessionTitleWriter aiChatSessionTitleWriter;

    /**
     * LLM 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션을 두 단계로 쪼갠다.
     * 1) existsById 로 세션 존재만 짧게 검증 (SimpleJpaRepository 의 자동 readOnly tx)
     * 2) 트랜잭션 밖에서 LLM 호출
     * 3) AiChatSessionTitleWriter 가 findById + updateTitle 만 짧은 트랜잭션으로 수행
     */
    public void execute(GenerateSessionTitleCommand command) {
        if (!aiChatSessionRepository.existsById(command.sessionId())) {
            throw new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND);
        }

        String generated;
        try {
            generated = aiChatTitleClient.generate(command.messages());
        } catch (RuntimeException e) {
            // 생성 실패로 빈 제목이 영구히 남지 않도록 기본 제목으로 대체하고,
            // 실패 자체는 리스너에서 로깅되도록 예외를 그대로 전파한다.
            aiChatSessionTitleWriter.applyDefaultTitleIfAbsent(command.sessionId());
            throw e;
        }

        if (generated == null || generated.isBlank()) {
            log.warn("세션 제목 생성 결과가 비어 있어 기본 제목으로 대체한다 sessionId={}", command.sessionId());
            aiChatSessionTitleWriter.applyDefaultTitleIfAbsent(command.sessionId());
            return;
        }

        aiChatSessionTitleWriter.updateTitle(command.sessionId(), generated);
    }
}
