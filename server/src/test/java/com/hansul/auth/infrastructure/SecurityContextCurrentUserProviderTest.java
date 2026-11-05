package com.hansul.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityContextCurrentUserProviderTest {

    private final SecurityContextCurrentUserProvider provider = new SecurityContextCurrentUserProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void derivesIdentityFromSubjectInsteadOfMutableProfileClaims() {
        Jwt jwt = new Jwt(
                "token",
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "keycloak-user-id",
                        "preferred_username", "cook",
                        "email", "cook@example.com"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        var user = provider.requireCurrentUser();

        assertThat(user.id().value()).isEqualTo("keycloak-user-id");
        assertThat(user.username()).isEqualTo("cook");
        assertThat(user.email()).isEqualTo("cook@example.com");
    }

    @Test
    void returnsEmptyWithoutAuthentication() {
        assertThat(provider.findCurrentUser()).isEmpty();
    }
}
