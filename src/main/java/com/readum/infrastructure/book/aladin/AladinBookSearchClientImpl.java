package com.readum.infrastructure.book.aladin;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.dto.BookSearchCommand;
import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.exception.BookErrorCode;
import com.readum.domain.book.out.BookSearchClient;
import com.readum.domain.exception.InternalServerErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AladinBookSearchClientImpl implements BookSearchClient {

    private static final String SEARCH_PATH = "/ItemSearch.aspx";
    private static final String QUERY_TYPE_KEYWORD = "Keyword";

    private final RestClient aladinRestClient;
    private final AladinProperties properties;

    // 알라딘은 한도(기본 200) 초과 Start 를 1페이지로 silent fallback 하므로
    // 우리 서버에서 사전 차단해 클라이언트의 무한 스크롤 종료 판단을 보장한다.
    @Override
    public BookSearchResult execute(BookSearchCommand command) {
        int start = (command.page() - 1) * command.size() + 1;
        if (start > properties.searchResultLimit()) {
            return new BookSearchResult(List.of(), 0, command.page(), command.size());
        }
        try {
            AladinItemSearchResponse response = aladinRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(SEARCH_PATH)
                            .queryParam("ttbkey", properties.ttbKey())
                            .queryParam("Query", command.keyword())
                            .queryParam("QueryType", QUERY_TYPE_KEYWORD)
                            .queryParam("SearchTarget", properties.searchTarget())
                            .queryParam("Start", start)
                            .queryParam("MaxResults", command.size())
                            .queryParam("Cover", properties.cover())
                            .queryParam("Output", properties.output())
                            .queryParam("Version", properties.version())
                            .build())
                    .retrieve()
                    .body(AladinItemSearchResponse.class);
            return buildBookSearchResult(response, command);
        } catch (RestClientException ex) {
            log.error("알라딘 도서 검색 호출 실패 keyword={}, page={}, size={}",
                    command.keyword(), command.page(), command.size(), ex);
            throw new InternalServerErrorException(BookErrorCode.ALADIN_SEARCH_FAILED);
        }
    }

    private BookSearchResult buildBookSearchResult(AladinItemSearchResponse response, BookSearchCommand command) {
        if (response == null) {
            return new BookSearchResult(List.of(), 0, command.page(), command.size());
        }
        int cappedTotal = Math.min(response.totalResults(), properties.searchResultLimit());
        List<AladinItemSearchResponse.Item> items = response.item() == null ? List.of() : response.item();
        List<BookResult> books = items.stream().map(this::mapItemToBookResult).toList();
        return new BookSearchResult(books, cappedTotal, command.page(), command.size());
    }

    private BookResult mapItemToBookResult(AladinItemSearchResponse.Item item) {
        return new BookResult(
                StringUtils.hasText(item.cover()) ? item.cover() : null,
                item.title(),
                item.author(),
                item.publisher(),
                extractPublishedYear(item.pubDate()),
                item.isbn13()
        );
    }

    private Integer extractPublishedYear(String pubDate) {
        if (!StringUtils.hasText(pubDate)) {
            return null;
        }
        try {
            return LocalDate.parse(pubDate).getYear();
        } catch (DateTimeParseException ignore) {
            // ISO 포맷이 아닐 때 앞 4자리 추출 폴백
        }
        if (pubDate.length() >= 4) {
            try {
                return Integer.parseInt(pubDate.substring(0, 4));
            } catch (NumberFormatException ignore) {
                // 폴백 실패 - 아래에서 로그 처리
            }
        }
        log.error("알라딘 pubDate 파싱 실패 - 예상치 못한 형식: pubDate={}", pubDate);
        return null;
    }
}
