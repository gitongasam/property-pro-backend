package com.propertypro.service;

import com.propertypro.dto.AuthDtos.AuthResponse;
import com.propertypro.dto.AuthDtos.ClientInfo;
import com.propertypro.dto.AuthDtos.LoginRequest;
import com.propertypro.dto.AuthDtos.MeResponse;
import com.propertypro.dto.AuthDtos.SignupRequest;
import com.propertypro.security.AuthenticatedUser;
import com.propertypro.tenancy.SystemExecutor;

/**
 * Sign-up, login, token refresh and logout.
 *
 * <p>All of this happens before an organisation is known (or across
 * organisations), so the database work runs through {@link SystemExecutor}.
 */
public interface AuthService {

    String OWNER_ROLE = "ORG_OWNER";

    AuthResponse signup(SignupRequest req, ClientInfo client);

    AuthResponse login(LoginRequest req, ClientInfo client);

    AuthResponse refresh(String rawRefreshToken, ClientInfo client);

    void logout(String rawRefreshToken);

    AuthResponse acceptInvite(String rawToken, String password, ClientInfo client);

    MeResponse me(AuthenticatedUser principal);

    /** Confirms the mobile number and returns fresh tokens that carry the confirmed flag. */
    AuthResponse verifyPhone(AuthenticatedUser principal, String code, ClientInfo client);

    /**
     * Changes the signed-in user's password after checking the current one. Every other
     * session is signed out; the caller gets fresh tokens.
     */
    AuthResponse changePassword(AuthenticatedUser principal, String currentPassword, String newPassword,
                                ClientInfo client);
}
