package com.readum.presentation.controller.userbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readum.domain.exception.ConflictException;
import com.readum.domain.userbook.dto.UserBookCreateResult;
import com.readum.domain.userbook.exception.UserBookErrorCode;
import com.readum.domain.userbook.service.UserBookCreateService;
import com.readum.presentation.common.GlobalExceptionHandler;
import com.readum.presentation.controller.userbook.dto.UserBookCreateRequest;
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
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserBookControllerTest {

    @Mock
    private UserBookCreateService userBookCreateService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        UserBookController controller = new UserBookController(userBookCreateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * standaloneSetup은 Spring Security 필터 체인 없이 동작하므로
     * SecurityMockMvcRequestPostProcessors.authentication()이 동작하지 않는다.
     * 대신 SecurityContextHolder에 직접 인증 정보를 세팅하는 post-processor를 사용한다.
     */
    private RequestPostProcessor authenticatedAs(Long userId) {
        return request -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            return request;
        };
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
                        .with(authenticatedAs(1L))
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
                        .with(authenticatedAs(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutExternalId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void userId가_null이면_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/user-books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
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
                        .with(authenticatedAs(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 책장에 등록된 도서입니다."))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.bookExternalId").value("9788965700807"));
    }
}
