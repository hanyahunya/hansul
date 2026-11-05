package com.hansul.auth.infrastructure;

import com.hansul.auth.api.AuthenticatedUser;
import com.hansul.auth.api.AuthenticatedUserId;
import com.hansul.auth.api.CurrentUserProvider;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Optional<AuthenticatedUser> findCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt) || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new AuthenticatedUser(
                new AuthenticatedUserId(jwt.getSubject()),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email")));
    }
}
