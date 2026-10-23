package com.propertypro.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/** The principal behind every authenticated request (built from the access token). */
public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        String userType,
        List<String> roles,
        List<String> permissions,
        /** False until the user confirms their mobile number with the SMS code. */
        boolean phoneVerified) {

    /** The current user, or an exception if the request is anonymous. */
    public static AuthenticatedUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("No authenticated user");
    }
}
