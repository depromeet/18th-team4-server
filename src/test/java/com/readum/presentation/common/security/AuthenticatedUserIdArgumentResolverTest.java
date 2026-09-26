package com.readum.presentation.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticatedUserIdArgumentResolverTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/authenticated-user-id")
        String probe(@AuthenticatedUserId Long userId) {
            return String.valueOf(userId);
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setCustomArgumentResolvers(new AuthenticatedUserIdArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void principal_의_userId_가_컨트롤러_파라미터로_주입된다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                7L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mockMvc.perform(get("/probe/authenticated-user-id"))
                .andExpect(status().isOk())
                .andExpect(content().string("7"));
    }

    @Test
    void principal_이_없으면_보안_설정_버그로_보고_즉시_실패한다() {
        // 인증 필요 경로는 Security 계층이 먼저 401 을 낸다 — resolver 까지 왔는데 principal 이
        // 없다는 건 설정 버그이므로, 조용한 대체 동작 없이 IllegalStateException 으로 드러낸다.
        AuthenticatedUserIdArgumentResolver resolver = new AuthenticatedUserIdArgumentResolver();

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
