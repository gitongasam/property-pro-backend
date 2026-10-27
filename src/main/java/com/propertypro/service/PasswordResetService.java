package com.propertypro.service;

/**
 * "Forgot password": a one-time link (valid 1 hour) sent by email, and by SMS to a
 * confirmed mobile number. Only a hash of the link token is stored.
 */
public interface PasswordResetService {

    /** Sends a reset link if the email has an active account; says nothing either way. */
    void requestReset(String email, String ipAddress);

    /** Sets the new password, uses up the link and signs the user out everywhere. */
    void resetPassword(String rawToken, String newPassword);
}
