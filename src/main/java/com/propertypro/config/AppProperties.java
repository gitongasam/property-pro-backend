package com.propertypro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Signup signup, Cors cors, Crypto crypto, Jobs jobs, Web web, Kcb kcb,
                            KcbStk kcbStk) {

    public record Jwt(String secret, long accessTokenMinutes, long refreshTokenDays) {}

    /** {@code phoneVerification}: new accounts must enter an SMS code before using the app. */
    public record Signup(String trialPlan, int trialDays, boolean phoneVerification) {}

    public record Cors(List<String> allowedOrigins) {}

    /** Base64 AES key (32 bytes) used to encrypt ID numbers, bank accounts and Daraja keys. */
    public record Crypto(String key) {}

    /** Scheduled jobs (billing, penalties, reminders, notification sending). */
    public record Jobs(boolean enabled) {}

    /** Public URLs: the frontend (for invite links) and this API (for M-Pesa callbacks). */
    public record Web(String frontendUrl, String publicApiUrl) {}

    /**
     * KCB Buni payment notifications (bank Paybill 522522). Every notification is signed by KCB
     * (SHA256withRSA over the body); {@code ipnPublicKey} is KCB's public key, base64 or PEM.
     * Turn {@code verifySignature} off only to post test notifications locally.
     */
    public record Kcb(String ipnPublicKey, boolean verifySignature) {}

    /**
     * Our own KCB Buni account, used to collect subscription payments by STK Push (not landlords' rent).
     * {@code environment} UAT talks to uat.buni.kcbgroup.com; {@code baseUrl} overrides the host.
     * {@code testAmount} replaces the plan price in UAT only, so a test upgrade costs KES 1.
     * {@code callbackToken} is a random secret in the callback URL: KCB's STK callback is not signed.
     */
    public record KcbStk(Environment environment, String baseUrl, String consumerKey, String consumerSecret,
                         String orgShortCode, String orgPassKey, boolean sharedShortCode, String invoicePrefix,
                         String callbackToken, Integer testAmount) {

        public enum Environment { UAT, PRODUCTION }
    }
}
