package com.propertypro.service;

import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");

    private final JdbcClient jdbc;

    private record Template(String subject, String body) {}

    /** Queues a message for the current organisation. Silently skips when there is no address. */
    @Override
    public void queue(Channel channel, Recipient recipientType, UUID recipientId, String to,
                      String templateCode, Map<String, ?> values) {
        if (to == null || to.isBlank()) {
            return;
        }
        Optional<Template> template = jdbc.sql("""
                        SELECT subject, body FROM message_templates
                         WHERE code = ? AND channel = ? AND active
                         ORDER BY organization_id NULLS LAST
                         LIMIT 1
                        """)
                .param(templateCode).param(channel.name())
                .query((rs, n) -> new Template(rs.getString("subject"), rs.getString("body")))
                .optional();
        if (template.isEmpty()) {
            log.warn("No {} template for {}; message not queued", channel, templateCode);
            return;
        }
        String body = render(template.get().body(), values);
        String subject = template.get().subject() == null ? null : render(template.get().subject(), values);
        insert(channel, recipientType, recipientId, to, templateCode, subject, body);
    }

    /** Queues a message whose text is already written (no template). */
    @Override
    public void queueText(Channel channel, Recipient recipientType, UUID recipientId, String to,
                          String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        insert(channel, recipientType, recipientId, to, null, subject, body);
    }

    /** True if this template was already sent to this recipient (used to avoid repeat reminders). */
    @Override
    public boolean alreadySent(String templateCode, UUID recipientId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE template_code = ? AND recipient_id = ?")
                .param(templateCode).param(recipientId)
                .query(Long.class).single() > 0;
    }

    private void insert(Channel channel, Recipient recipientType, UUID recipientId, String to,
                        String templateCode, String subject, String body) {
        jdbc.sql("""
                        INSERT INTO notifications (organization_id, channel, recipient_type, recipient_id, to_address,
                                                   template_code, subject, body, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                        """)
                .param(TenantContext.requireOrganizationId())
                .param(channel.name()).param(recipientType.name()).param(recipientId).param(to.trim())
                .param(templateCode).param(subject).param(body)
                .update();
    }

    static String render(String text, Map<String, ?> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = values == null ? null : values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
