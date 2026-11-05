package com.hansul.auth.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hansul.auth.api.CurrentUserController;
import com.hansul.auth.infrastructure.SecurityContextCurrentUserProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {
        CurrentUserController.class,
        HealthProbeTestController.class
})
@Import({
        SecurityConfig.class,
        SecurityContextCurrentUserProvider.class,
        SecurityConfigTest.JwtTestConfiguration.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnauthenticatedV1Requests() throws Exception {
        mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leavesHealthEndpointPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void exposesStableSubjectAsApplicationUserId() throws Exception {
        mockMvc.perform(get("/v1/auth/me")
                        .with(jwt().jwt(token -> token
                                .subject("248289f8-ffdf-4c68-a93f-a9a0d38b7d9a")
                                .audience(List.of("hansul-api"))
                                .claim("preferred_username", "cook")
                                .claim("email", "cook@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("248289f8-ffdf-4c68-a93f-a9a0d38b7d9a"))
                .andExpect(jsonPath("$.username").value("cook"))
                .andExpect(jsonPath("$.email").value("cook@example.com"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("JWT decoding is not used by MockMvc jwt() tests");
            };
        }
    }
}

@RestController
class HealthProbeTestController {

    @GetMapping("/actuator/health")
    String health() {
        return "UP";
    }
}
