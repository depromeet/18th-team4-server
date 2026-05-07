package com.readum.domain.user.userbook.service;

import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.exception.UserErrorCode;
import com.readum.domain.user.userbook.dto.UserBookSearchItemResult;
import com.readum.domain.user.userbook.dto.UserBookSearchResult;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserBookRepository;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBookSearchService {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;

    public UserBookSearchResult findMyBooks(String userSessionId) {
        User user = userRepository.findBySessionId(userSessionId)
                .orElseThrow(() -> new UnauthorizedException(UserErrorCode.INVALID_SESSION));

        List<UserBookSearchItemResult> books = userBookRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream()
                .map(UserBookSearchItemResult::from)
                .toList();

        return new UserBookSearchResult(books);
    }
}
