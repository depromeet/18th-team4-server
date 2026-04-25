package com.readum.domain.book.service;

import com.readum.domain.book.dto.BookSearchCommand;
import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.out.BookSearchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookSearchService {

    private final BookSearchClient bookSearchClient;

    public BookSearchResult search(BookSearchCommand command) {
        return bookSearchClient.execute(command);
    }
}
