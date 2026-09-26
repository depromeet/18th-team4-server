package com.readum.presentation.controller.userBook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.exception.NotFoundException;
import com.readum.domain.userBook.dto.UserBookCreateResult;
import com.readum.domain.userBook.dto.UserBookDeleteResult;
import com.readum.domain.userBook.dto.UserBookSearchItemResult;
import com.readum.domain.userBook.dto.UserBookSearchResult;
import com.readum.domain.userBook.exception.UserBookErrorCode;
import com.readum.domain.userBook.service.UserBookCreateService;
import com.readum.domain.userBook.service.UserBookDeleteService;
import com.readum.domain.userBook.service.UserBookSearchService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.common.security.AuthenticatedUserIdArgumentResolver;
import com.readum.presentation.controller.userBook.dto.UserBookCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserBookControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserBookCreateService userBookCreateService;

    @Mock
    private UserBookSearchService userBookSearchService;

    @Mock
    private UserBookDeleteService userBookDeleteService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        UserBookController controller =
                new UserBookController(userBookCreateService, userBookSearchService, userBookDeleteService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedUserIdArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String validRequestBody() throws Exception {
        return objectMapper.writeValueAsString(new UserBookCreateRequest("9788965700807"));
    }

    @Test
    void 유효한_요청_시_201과_Location_헤더_및_등록된_UserBook_정보를_반환한다() throws Exception {
        UserBookCreateResult result = new UserBookCreateResult(
                100L, 1L, "9788965700807", "테스트 책", "테스트 저자",
                "테스트 출판사", 2024, "http://example.com/cover.jpg",
                LocalDateTime.of(2024, 6, 1, 12, 0)
        );
        given(userBookCreateService.execute(any())).willReturn(result);

        mockMvc.perform(post("/api/v1/user-books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/api/v1/user-books/100"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.bookExternalId").value("9788965700807"))
                .andExpect(jsonPath("$.data.title").value("테스트 책"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void bookExternalId가_없으면_400을_반환한다() throws Exception {
        String bodyWithoutExternalId = objectMapper.writeValueAsString(new UserBookCreateRequest(null));

        mockMvc.perform(post("/api/v1/user-books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutExternalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void 이미_등록된_도서면_409와_error_payload를_GlobalExceptionHandler가_직렬화하여_반환한다() throws Exception {
        UserBookCreateResult existingResult = new UserBookCreateResult(
                100L, 1L, "9788965700807", "테스트 책", "테스트 저자",
                "테스트 출판사", 2024, "http://example.com/cover.jpg",
                LocalDateTime.of(2024, 6, 1, 12, 0)
        );
        given(userBookCreateService.execute(any()))
                .willThrow(new ConflictException(UserBookErrorCode.ALREADY_EXISTS, existingResult));

        mockMvc.perform(post("/api/v1/user-books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 책장에 등록된 도서입니다."))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.bookExternalId").value("9788965700807"));
    }

    @Test
    void 인증된_사용자가_GET_요청시_200과_등록_도서_목록을_반환한다() throws Exception {
        UserBookSearchResult searchResult = new UserBookSearchResult(List.of(
                new UserBookSearchItemResult(20L, 2L, "최근 등록한 책", "출판사B", 2025, "http://example.com/b.jpg", 3L),
                new UserBookSearchItemResult(10L, 1L, "이전에 등록한 책", "출판사A", 2024, "http://example.com/a.jpg", 0L)
        ));
        given(userBookSearchService.findMyBooks(USER_ID)).willReturn(searchResult);

        mockMvc.perform(get("/api/v1/user-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.books.length()").value(2))
                .andExpect(jsonPath("$.data.books[0].userBookId").value(20))
                .andExpect(jsonPath("$.data.books[0].bookId").value(2))
                .andExpect(jsonPath("$.data.books[0].title").value("최근 등록한 책"))
                .andExpect(jsonPath("$.data.books[0].publisher").value("출판사B"))
                .andExpect(jsonPath("$.data.books[0].publishedYear").value(2025))
                .andExpect(jsonPath("$.data.books[0].coverUrl").value("http://example.com/b.jpg"))
                .andExpect(jsonPath("$.data.books[0].chatSessionCount").value(3))
                .andExpect(jsonPath("$.data.books[1].userBookId").value(10))
                .andExpect(jsonPath("$.data.books[1].bookId").value(1))
                .andExpect(jsonPath("$.data.books[1].title").value("이전에 등록한 책"))
                .andExpect(jsonPath("$.data.books[1].chatSessionCount").value(0))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 등록_도서가_없으면_빈_books_배열을_200과_함께_반환한다() throws Exception {
        given(userBookSearchService.findMyBooks(USER_ID))
                .willReturn(new UserBookSearchResult(List.of()));

        mockMvc.perform(get("/api/v1/user-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.books.length()").value(0))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void DELETE_요청_시_204를_본문_없이_반환하고_경로의_userBookId와_인증된_userId로_삭제를_위임한다() throws Exception {
        given(userBookDeleteService.execute(any()))
                .willReturn(new UserBookDeleteResult(100L, 3, 7, 2));

        mockMvc.perform(delete("/api/v1/user-books/100"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(userBookDeleteService).execute(argThat(command ->
                command.userId().equals(USER_ID)
                        && command.userBookId().equals(100L)));
    }

    @Test
    void DELETE_요청에_본인_책장에_없는_도서면_404를_반환한다() throws Exception {
        given(userBookDeleteService.execute(any()))
                .willThrow(new NotFoundException(UserBookErrorCode.NOT_FOUND));

        mockMvc.perform(delete("/api/v1/user-books/100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("등록되지 않은 도서입니다."));
    }
}
