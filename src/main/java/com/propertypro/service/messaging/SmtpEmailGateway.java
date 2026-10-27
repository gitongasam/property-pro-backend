package com.propertypro.service.messaging;

import com.propertypro.config.AppProperties;
import com.propertypro.integration.MessageGateways.EmailGateway;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/**
 * Sends email over SMTP. Active only when app.mail.host is set; it then replaces the
 * logging gateway, so invitations, trial and renewal reminders and password resets go out.
 */
@Service
@ConditionalOnExpression("'${app.mail.host:}' != ''")
public class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final String fromName;

    public SmtpEmailGateway(AppProperties props) {
        AppProperties.Mail cfg = props.mail();
        int port = cfg.port() == null ? 587 : cfg.port();
        sender.setHost(cfg.host().trim());
        sender.setPort(port);
        sender.setUsername(blankToNull(cfg.username()));
        sender.setPassword(blankToNull(cfg.password()));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(cfg.username() != null && !cfg.username().isBlank()));
        if (port == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");

        this.from = blankToNull(cfg.from()) != null ? cfg.from().trim() : sender.getUsername();
        this.fromName = cfg.fromName() == null || cfg.fromName().isBlank() ? "PropertyPro" : cfg.fromName().trim();
        if (this.from == null) {
            throw new IllegalStateException("Set MAIL_FROM (or MAIL_USERNAME) to send email");
        }
        log.info("Email goes out through SMTP {}:{} as {}", cfg.host(), port, from);
    }

    /** Throws on failure so the notification queue marks the message FAILED and retries it. */
    @Override
    public String send(String toEmail, String subject, String html) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from, fromName);
            helper.setTo(toEmail.trim());
            helper.setSubject(subject == null || subject.isBlank() ? "PropertyPro" : subject);
            helper.setText(html, true);
            String id = "smtp-" + UUID.randomUUID();
            message.setHeader("X-PropertyPro-Id", id);
            sender.send(message);
            log.info("Email sent to {}: {}", mask(toEmail), subject);
            return id;
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new IllegalStateException("Could not build email: " + e.getMessage(), e);
        } catch (MailException e) {
            throw new IllegalStateException("SMTP refused the email: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** jane.doe@x.com -> ja***@x.com */
    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 2 ? "***" + email.substring(Math.max(at, 0)) : email.substring(0, 2) + "***" + email.substring(at);
    }
}
