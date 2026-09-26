package com.readum.presentation.common.security;

import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionCookieAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 유효한_세션_쿠키로_인증_필요_경로에_접근하면_200_을_받는다() throws Exception {
        UUID sessionId = UUID.randomUUID();
        userRepository.save(User.create(sessionId, "책읽는여우"));

        mockMvc.perform(get("/api/v1/users/me/profile")
                        .cookie(new Cookie("user_session", sessionId.toString())))
                .andExpect(status().isOk());
    }

    @Test
    void 쿠키_없이_인증_필요_경로에_접근하면_Security_계층이_401_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 무효한_세션_쿠키면_401_을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile")
                        .cookie(new Cookie("user_session", "no-such-session")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 쿠키_발급_엔드포인트는_익명으로_접근할_수_있다() throws Exception {
        mockMvc.perform(post("/api/v1/users/sessions"))
                .andExpect(status().isCreated());
    }
}
