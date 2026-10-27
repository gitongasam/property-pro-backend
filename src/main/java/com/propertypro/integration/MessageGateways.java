package com.propertypro.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Where messages actually go. The defaults only log, so the app runs without
 * accounts. Real gateways replace them automatically when configured:
 * ZettatelSmsProvider (zettatel.*) and SmtpEmailGateway (app.mail.host).
 */
@Configuration
public class MessageGateways {

    public interface SmsGateway {
        /** Sends one SMS; returns the provider's message id. Throws on failure. */
        String send(String toPhone, String text);
    }

    public interface EmailGateway {
        String send(String toEmail, String subject, String html);
    }

    @Slf4j
    static class LoggingSmsGateway implements SmsGateway {
        @Override
        public String send(String toPhone, String text) {
            log.info("[SMS not sent: no provider configured] to={} text={}", toPhone, text);
            return "log-" + UUID.randomUUID();
        }
    }

    @Slf4j
    static class LoggingEmailGateway implements EmailGateway {
        @Override
        public String send(String toEmail, String subject, String html) {
            log.info("[Email not sent: no provider configured] to={} subject={}", toEmail, subject);
            return "log-" + UUID.randomUUID();
        }
    }

    @Bean
    @ConditionalOnMissingBean(SmsGateway.class)
    SmsGateway loggingSmsGateway() {
        return new LoggingSmsGateway();
    }

    @Bean
    @ConditionalOnMissingBean(EmailGateway.class)
    EmailGateway loggingEmailGateway() {
        return new LoggingEmailGateway();
    }
}
