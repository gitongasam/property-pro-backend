package com.propertypro.controller;

import com.propertypro.dto.AuthDtos.AcceptInviteRequest;
import com.propertypro.dto.AuthDtos.AuthResponse;
import com.propertypro.dto.AuthDtos.ClientInfo;
import com.propertypro.dto.AuthDtos.CodeSentResponse;
import com.propertypro.dto.AuthDtos.LoginRequest;
import com.propertypro.dto.AuthDtos.MeResponse;
import com.propertypro.dto.AuthDtos.RefreshRequest;
import com.propertypro.dto.AuthDtos.SignupRequest;
import com.propertypro.dto.AuthDtos.VerifyPhoneRequest;
import com.propertypro.security.AuthenticatedUser;
import com.propertypro.service.AuthService;
import com.propertypro.service.PhoneVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PhoneVerificationService phoneVerification;

    @Operation(summary = "Create an organisation with a free trial and its first (owner) user")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        return authService.signup(body, client(request));
    }

    @Operation(summary = "Sign in with email and password")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authService.login(body, client(request));
    }

    @Operation(summary = "Exchange a refresh token for new tokens (the old refresh token stops working)")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        return authService.refresh(body.refreshToken(), client(request));
    }

    @Operation(summary = "Sign out: revoke the refresh token")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest body) {
        authService.logout(body.refreshToken());
    }

    @Operation(summary = "Accept a staff invitation: set a password and sign in")
    @PostMapping("/accept-invite")
    public AuthResponse acceptInvite(@Valid @RequestBody AcceptInviteRequest body, HttpServletRequest request) {
        return authService.acceptInvite(body.token(), body.password(), client(request));
    }

    @Operation(summary = "Current user, organisation, permissions and plan")
    @GetMapping("/me")
    public MeResponse me() {
        return authService.me(AuthenticatedUser.current());
    }

    @Operation(summary = "Send (or resend) the 6-digit confirmation code to the signed-in user's phone")
    @PostMapping("/phone/send-code")
    public CodeSentResponse sendPhoneCode() {
        return phoneVerification.sendCode(AuthenticatedUser.current().userId());
    }

    @Operation(summary = "Confirm the mobile number; returns new tokens that unlock the rest of the app")
    @PostMapping("/phone/verify")
    public AuthResponse verifyPhone(@Valid @RequestBody VerifyPhoneRequest body, HttpServletRequest request) {
        return authService.verifyPhone(AuthenticatedUser.current(), body.code(), client(request));
    }

    private static ClientInfo client(HttpServletRequest request) {
        // getRemoteAddr() is the real client IP when server.forward-headers-strategy is set behind a proxy
        return new ClientInfo(request.getHeader("User-Agent"), request.getRemoteAddr());
    }
}
