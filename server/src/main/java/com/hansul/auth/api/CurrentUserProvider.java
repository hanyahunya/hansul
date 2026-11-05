package com.hansul.auth.api;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * Application-facing access to the current principal. Ownership must be stored against
 * {@link AuthenticatedUserId}; username and email are mutable display attributes.
 */
public interface CurrentUserProvider {

    Optional<AuthenticatedUser> findCurrentUser();

    default AuthenticatedUser requireCurrentUser() {
        return findCurrentUser().orElseThrow(() ->
                new AuthenticationCredentialsNotFoundException("An authenticated user is required"));
    }
}
