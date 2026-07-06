package com.readum.domain.user.userbook.service;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.out.BookLookupClient;
import com.readum.domain.user.userbook.dto.UserBookCreateCommand;
import com.readum.domain.user.userbook.dto.UserBookCreateResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class UserBookCreateService {

    private final BookLookupClient bookLookupClient;
    private final UserBookRegistrationWriter userBookRegistrationWriter;

    public UserBookCreateResult execute(UserBookCreateCommand command) {
        // 알라딘 도서 정보 조회 (신뢰할 수 있는 원천 데이터 확보).
        // 외부 HTTP 호출이 DB 커넥션을 점유하지 않도록 트랜잭션 시작 전에 끝낸다.
        BookResult bookInfo = bookLookupClient.execute(command.bookExternalId());

        return userBookRegistrationWriter.register(command, bookInfo);
    }
}
