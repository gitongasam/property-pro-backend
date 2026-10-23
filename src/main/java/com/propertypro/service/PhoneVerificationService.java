package com.propertypro.service;

import com.propertypro.dto.AuthDtos.CodeSentResponse;

import java.util.UUID;

/**
 * Confirms a user's mobile number with a 6-digit SMS code. Until it is confirmed
 * the account can only reach /api/v1/auth/** (see JwtAuthenticationFilter).
 */
public interface PhoneVerificationService {

    /** Sends a new code to the user's phone, replacing any earlier one. Rate limited. */
    CodeSentResponse sendCode(UUID userId);

    /** Marks the phone confirmed, or throws if the code is wrong, expired or used up. */
    void verify(UUID userId, String code);
}
