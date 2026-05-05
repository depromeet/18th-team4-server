package com.readum.domain.aiChat.service;

import com.readum.domain.aiChat.dto.GenerateSessionTitleCommand;
import com.readum.domain.aiChat.exception.AiChatErrorCode;
import com.readum.domain.aiChat.out.AiChatTitleClient;
import com.readum.domain.exception.NotFoundException;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 채팅 세션의 제목을 생성·갱신하는 Command 서비스.
 * 트리거(첫 메시지 / 향후 N턴 재생성 / 수동 재명명 등) 와 무관하게 동일 진입점을 통해 동작한다.
 * 호출자가 첫 USER 메시지를 알고 있는 경우(첫 메시지 트리거) 그대로 전달하고,
 * 모르는 경우(재생성) 별도 로직으로 메시지를 가져와 command 에 담아 호출하면 된다.
 */
@Slf4j
@Service
public class AiChatSessionTitleService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatTitleClient aiChatTitleClient;
    private final TransactionTemplate transactionTemplate;

    public AiChatSessionTitleService(
            AiChatSessionRepository aiChatSessionRepository,
            AiChatTitleClient aiChatTitleClient,
            PlatformTransactionManager transactionManager
    ) {
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.aiChatTitleClient = aiChatTitleClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * LLM 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션을 두 단계로 쪼갠다.
     * 1) existsById 로 세션 존재만 짧게 검증 (SimpleJpaRepository 의 자동 readOnly tx)
     * 2) 트랜잭션 밖에서 LLM 호출
     * 3) findById + updateTitle 만 짧은 트랜잭션으로 묶어 dirty-check flush 를 보장
     */
    public void execute(GenerateSessionTitleCommand command) {
        if (!aiChatSessionRepository.existsById(command.sessionId())) {
            throw new NotFoundException(AiChatErrorCode.SESSION_NOT_FOUND);
        }

        String generated = aiChatTitleClient.generate(command.firstUserMessage());
        if (generated == null || generated.isBlank()) {
            log.warn("세션 제목 생성 결과가 비어 있어 갱신을 skip 한다 sessionId={}", command.sessionId());
            return;
        }

        transactionTemplate.executeWithoutResult(status ->
                aiChatSessionRepository.findById(command.sessionId())
                        .ifPresent(session -> session.updateTitle(generated)));
    }
}
