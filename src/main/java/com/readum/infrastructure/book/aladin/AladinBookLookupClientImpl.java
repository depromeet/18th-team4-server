package com.readum.infrastructure.book.aladin;

import com.readum.domain.book.dto.BookResult;
import com.readum.domain.book.exception.BookErrorCode;
import com.readum.domain.book.out.BookLookupClient;
import com.readum.domain.exception.BadGatewayException;
import com.readum.domain.exception.ExternalApiException;
import com.readum.domain.exception.GatewayTimeoutException;
import com.readum.domain.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AladinBookLookupClientImpl implements BookLookupClient {

    private static final String LOOKUP_PATH = "/ItemLookUp.aspx";
    private static final String ITEM_ID_TYPE_ISBN13 = "ISBN13";

    private final RestClient aladinRestClient;
    private final AladinProperties properties;

    @Override
    public BookResult execute(String isbn13) {
        try {
            AladinItemSearchResponse response = aladinRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(LOOKUP_PATH)
                            .queryParam("ttbkey", properties.ttbKey())
                            .queryParam("ItemId", isbn13)
                            .queryParam("ItemIdType", ITEM_ID_TYPE_ISBN13)
                            .queryParam("Cover", properties.cover())
                            .queryParam("Output", properties.output())
                            .queryParam("Version", properties.version())
                            .build())
                    .retrieve()
                    .body(AladinItemSearchResponse.class);

            if (response == null || response.item() == null || response.item().isEmpty()) {
                throw new NotFoundException(BookErrorCode.LOOKUP_NOT_FOUND);
            }

            return mapToBookResult(response.item().get(0));
        } catch (NotFoundException ex) {
            throw ex;
        } catch (HttpServerErrorException ex) {
            log.error("도서 조회 외부 응답 5xx isbn={}, status={}", isbn13, ex.getStatusCode(), ex);
            throw new BadGatewayException(BookErrorCode.LOOKUP_GATEWAY_ERROR);
        } catch (ResourceAccessException ex) {
            log.error("도서 조회 외부 응답 시간 초과 또는 IO 실패 isbn={}", isbn13, ex);
            throw new GatewayTimeoutException(BookErrorCode.LOOKUP_TIMEOUT);
        } catch (RestClientException ex) {
            log.error("도서 조회 외부 호출 실패 isbn={}", isbn13, ex);
            throw new ExternalApiException(BookErrorCode.LOOKUP_FAILED);
        }
    }

    private BookResult mapToBookResult(AladinItemSearchResponse.Item item) {
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
        log.warn("알라딘 pubDate 파싱 폴백 실패 - 예상치 못한 형식: pubDate={}", pubDate);
        return null;
    }
}
