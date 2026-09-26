package com.readum.domain.userBook.dto;

/**
 * 등록 도서 삭제 결과. 응답 바디로 직렬화하지 않고(삭제 응답은 204 No Content),
 * 서비스의 관찰성 로그(삭제된 세션/메시지/감상 행 수)용으로만 사용한다.
 */
public record UserBookDeleteResult(
        Long userBookId,
        int deletedSessions,
        int deletedMessages,
        int deletedSummaries
) {
}
