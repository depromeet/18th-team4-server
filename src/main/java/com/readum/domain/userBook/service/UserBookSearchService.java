package com.readum.domain.userBook.service;

import com.readum.domain.userBook.dto.UserBookSearchItemResult;
import com.readum.domain.userBook.dto.UserBookSearchResult;
import com.readum.model.userBook.repository.UserBookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBookSearchService {

    private final UserBookRepository userBookRepository;

    public UserBookSearchResult findMyBooks(Long userId) {
        List<UserBookSearchItemResult> books = userBookRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId)
                .stream()
                .map(UserBookSearchItemResult::from)
                .toList();

        return new UserBookSearchResult(books);
    }
}
