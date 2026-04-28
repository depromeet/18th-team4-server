package com.readum.domain.book.out;

import com.readum.domain.book.dto.BookResult;

public interface BookLookupClient {

    BookResult execute(String isbn13);
}
