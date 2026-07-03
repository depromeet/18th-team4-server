package com.readum.presentation.controller.auth;

import com.readum.domain.auth.service.LogoutService;
import com.readum.domain.auth.service.TokenRefreshService;
import com.readum.presentation.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerRefreshTest {

    private static final String REFRESH_URI = "/api/v1/auth/refresh";

    @Mock
    private TokenRefreshService tokenRefreshService;

    @Mock
    private LogoutService logoutService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(tokenRefreshService, logoutService, true);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void RT_쿠키_없이_재발급을_요청하면_401_을_반환한다() throws Exception {
        mockMvc.perform(post(REFRESH_URI))
                .andExpect(status().isUnauthorized());
    }
}
