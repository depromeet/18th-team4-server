package com.readum.domain.book.out;

import com.readum.domain.book.dto.BookSearchCommand;
import com.readum.domain.book.dto.BookSearchResult;

public interface AladinBookSearchClient {

    BookSearchResult execute(BookSearchCommand command);
}
