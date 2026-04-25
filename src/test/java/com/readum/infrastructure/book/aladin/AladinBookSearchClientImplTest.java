package com.readum.infrastructure.book.aladin;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.dto.BookSearchCommand;
import com.readum.domain.book.dto.BookSearchResult;
import com.readum.domain.book.exception.BookErrorCode;
import com.readum.domain.exception.InternalServerErrorException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AladinBookSearchClientImplTest {

    private static final String BASE_URL = "http://test.aladin.local/ttb/api";

    private MockRestServiceServer mockServer;
    private AladinBookSearchClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        AladinProperties properties = new AladinProperties(
                "test-ttb-key",
                BASE_URL,
                "Book",
                "JS",
                "20131101",
                "Big",
                Duration.ofSeconds(1),
                200
        );
        this.client = new AladinBookSearchClientImpl(restClient, properties);
    }

    @Test
    void 정상_응답을_BookSearchResult로_매핑한다() {
        String responseJson = """
                {
                  "totalResults": 100,
                  "startIndex": 1,
                  "itemsPerPage": 10,
                  "item": [
                    {
                      "title": "리액트를 다루는 기술",
                      "author": "김민준",
                      "pubDate": "2024-03-15",
                      "publisher": "길벗",
                      "cover": "https://image.aladin.co.kr/cover.jpg",
                      "isbn13": "9788966262281"
                    }
                  ]
                }
                """;
        mockServer.expect(requestToUriTemplate(BASE_URL + "/ItemSearch.aspx?ttbkey={ttbkey}&Query={q}&QueryType={qt}&SearchTarget={st}&Start={start}&MaxResults={max}&Cover={cover}&Output={out}&Version={ver}",
                        "test-ttb-key", "리액트", "Keyword", "Book", 1, 10, "Big", "JS", "20131101"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BookSearchResult result = client.execute(new BookSearchCommand("리액트", 1, 10));

        assertThat(result.totalResultCount()).isEqualTo(100);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.books()).hasSize(1);
        BookResult book = result.books().get(0);
        assertThat(book.title()).isEqualTo("리액트를 다루는 기술");
        assertThat(book.author()).isEqualTo("김민준");
        assertThat(book.publisher()).isEqualTo("길벗");
        assertThat(book.publishedYear()).isEqualTo(2024);
        assertThat(book.coverUrl()).isEqualTo("https://image.aladin.co.kr/cover.jpg");
        assertThat(book.isbn13()).isEqualTo("9788966262281");
    }

    @Test
    void page_2_요청시_Start_파라미터가_11로_변환된다() {
        mockServer.expect(queryParam("Start", "11"))
                .andExpect(queryParam("MaxResults", "10"))
                .andRespond(withSuccess("""
                        {"totalResults":0,"startIndex":11,"itemsPerPage":10,"item":[]}
                        """, MediaType.APPLICATION_JSON));

        client.execute(new BookSearchCommand("react", 2, 10));

        mockServer.verify();
    }

    @Test
    void item이_없으면_빈_리스트를_반환한다() {
        mockServer.expect(queryParam("Query", "nomatch"))
                .andRespond(withSuccess("""
                        {"totalResults":0,"startIndex":1,"itemsPerPage":10}
                        """, MediaType.APPLICATION_JSON));

        BookSearchResult result = client.execute(new BookSearchCommand("nomatch", 1, 10));

        assertThat(result.books()).isEmpty();
        assertThat(result.totalResultCount()).isZero();
    }

    @Test
    void 알라딘_5xx_응답시_InternalServerErrorException을_던진다() {
        mockServer.expect(queryParam("Query", "react"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.execute(new BookSearchCommand("react", 1, 10)))
                .asInstanceOf(InstanceOfAssertFactories.type(InternalServerErrorException.class))
                .extracting(InternalServerErrorException::getErrorCode)
                .isEqualTo(BookErrorCode.ALADIN_SEARCH_FAILED);
    }

    @Test
    void 알라딘_4xx_응답시_InternalServerErrorException을_던진다() {
        mockServer.expect(queryParam("Query", "react"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.execute(new BookSearchCommand("react", 1, 10)))
                .asInstanceOf(InstanceOfAssertFactories.type(InternalServerErrorException.class))
                .extracting(InternalServerErrorException::getErrorCode)
                .isEqualTo(BookErrorCode.ALADIN_SEARCH_FAILED);
    }

    @Test
    void 검색_한도_초과_페이지_요청시_알라딘_호출없이_빈_결과를_반환한다() {
        // start = (11-1)*20 + 1 = 201 > 200 → 알라딘 호출 안 함
        BookSearchResult result = client.execute(new BookSearchCommand("react", 11, 20));

        assertThat(result.books()).isEmpty();
        assertThat(result.totalResultCount()).isZero();
        assertThat(result.page()).isEqualTo(11);
        assertThat(result.size()).isEqualTo(20);
        // 알라딘에 어떤 호출도 없었음을 검증
        mockServer.verify();
    }

    @Test
    void 알라딘_totalResults가_한도_초과면_totalResultCount는_한도로_cap된다() {
        mockServer.expect(queryParam("Query", "react"))
                .andRespond(withSuccess("""
                        {"totalResults":539,"startIndex":1,"itemsPerPage":20,"item":[]}
                        """, MediaType.APPLICATION_JSON));

        BookSearchResult result = client.execute(new BookSearchCommand("react", 1, 20));

        assertThat(result.totalResultCount()).isEqualTo(200);
    }
}
