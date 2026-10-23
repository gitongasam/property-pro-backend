package com.propertypro.scheduler;

import com.propertypro.integration.MessageGateways.EmailGateway;
import com.propertypro.integration.MessageGateways.SmsGateway;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sends queued notifications every 30 seconds, across all organisations.
 * FOR UPDATE SKIP LOCKED lets several app instances share the work safely.
 * A failed message is retried up to 5 times.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDispatcher {

    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final SmsGateway sms;
    private final EmailGateway email;

    private record Pending(UUID id, String channel, String to, String subject, String body) {}

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    public void sendQueued() {
        system.asSystem(() -> {
            List<Pending> batch = jdbc.sql("""
                            SELECT id, channel, to_address, subject, body
                              FROM notifications
                             WHERE status IN ('QUEUED','FAILED') AND attempts < ? AND channel <> 'IN_APP'
                             ORDER BY created_at
                             LIMIT ?
                             FOR UPDATE SKIP LOCKED
                            """)
                    .param(MAX_ATTEMPTS).param(BATCH)
                    .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("channel"),
                            rs.getString("to_address"), rs.getString("subject"), rs.getString("body")))
                    .list();
            for (Pending p : batch) {
                try {
                    String providerId = "SMS".equals(p.channel())
                            ? sms.send(p.to(), p.body())
                            : email.send(p.to(), p.subject(), p.body());
                    int segments = "SMS".equals(p.channel()) ? Math.max(1, (p.body().length() + 152) / 153) : 0;
                    jdbc.sql("""
                                    UPDATE notifications SET status = 'SENT', sent_at = now(), attempts = attempts + 1,
                                           provider_message_id = ?, sms_segments = ?, error = NULL
                                     WHERE id = ?""")
                            .param(providerId).param(segments).param(p.id()).update();
                } catch (RuntimeException e) {
                    log.warn("Sending notification {} failed: {}", p.id(), e.getMessage());
                    jdbc.sql("UPDATE notifications SET status = 'FAILED', attempts = attempts + 1, error = ? WHERE id = ?")
                            .param(String.valueOf(e.getMessage())).param(p.id()).update();
                }
            }
            return null;
        });
    }
}
