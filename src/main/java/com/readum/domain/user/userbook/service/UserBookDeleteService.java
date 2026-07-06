package com.readum.domain.user.userbook.service;

import com.readum.domain.exception.NotFoundException;
import com.readum.domain.user.userbook.dto.UserBookDeleteCommand;
import com.readum.domain.user.userbook.dto.UserBookDeleteResult;
import com.readum.domain.user.userbook.exception.UserBookErrorCode;
import com.readum.model.aiChat.repository.AiChatMessageRepository;
import com.readum.model.aiChat.repository.AiChatSessionRepository;
import com.readum.model.summary.repository.SummaryJobRepository;
import com.readum.model.summary.repository.SummaryRepository;
import com.readum.model.userBook.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 도서(UserBook) 삭제 + 연관 데이터 cascade 삭제.
 * <p>
 * 프로젝트가 의도적으로 DB FK / JPA cascade 를 두지 않고 느슨한 Long id 참조로 설계했으므로,
 * UserBook 을 가리키는 모든 데이터(대화 세션·메시지·감상·사용자 마지막선택 참조)를
 * 서비스 계층에서 한 트랜잭션으로 명시 삭제한다.
 * <p>
 * 삭제 순서가 중요하다 — 메시지는 session_id 를 통해 세션을 거쳐 좁혀지므로
 * 반드시 메시지 → 세션 순으로 삭제한다 (세션이 먼저 사라지면 메시지가 고아로 남는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBookDeleteService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final AiChatSessionRepository aiChatSessionRepository;
    private final SummaryRepository summaryRepository;
    private final SummaryJobRepository summaryJobRepository;

    @Transactional
    public UserBookDeleteResult execute(UserBookDeleteCommand command) {
        // 소유권 검증. 미소유/미존재를 구분하지 않고 404 로 응답해 타인 도서의 존재 여부를 누설하지 않는다.
        // 여기서 로드한 엔티티는 인가 판정·로깅에만 쓴다 — 첫 벌크 삭제(clearAutomatically) 시점에
        // 영속성 컨텍스트가 비워져 detached 가 되므로, 실제 부모 삭제는 deleteById(id) 로 수행한다.
        userBookRepository.findByIdAndUserId(command.userBookId(), command.userId())
                .orElseThrow(() -> new NotFoundException(UserBookErrorCode.NOT_FOUND));

        Long userBookId = command.userBookId();

        int deletedMessages = aiChatMessageRepository.deleteAllByUserBookId(userBookId);
        // summary_job 은 세션 서브쿼리로 좁히므로 세션 삭제 전에 먼저 지운다(메시지와 같은 이유).
        int deletedJobs = summaryJobRepository.deleteAllByUserBookId(userBookId);
        int deletedSessions = aiChatSessionRepository.deleteAllByUserBookId(userBookId);
        int deletedSummaries = summaryRepository.deleteAllByUserBookId(userBookId);
        userRepository.clearLastSelectedUserBook(userBookId);
        userBookRepository.deleteById(userBookId);

        log.info("등록 도서 삭제 완료 - userId={}, userBookId={}, deletedSessions={}, deletedMessages={}, deletedJobs={}, deletedSummaries={}",
                command.userId(), userBookId, deletedSessions, deletedMessages, deletedJobs, deletedSummaries);

        return new UserBookDeleteResult(userBookId, deletedSessions, deletedMessages, deletedSummaries);
    }
}
