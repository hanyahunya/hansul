package com.hansul.auth.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class CurrentUserController {

    private final CurrentUserProvider currentUserProvider;

    public CurrentUserController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/me")
    CurrentUserResponse me() {
        AuthenticatedUser user = currentUserProvider.requireCurrentUser();
        return new CurrentUserResponse(user.id().value(), user.username(), user.email());
    }

    public record CurrentUserResponse(String userId, String username, String email) {
    }
}
