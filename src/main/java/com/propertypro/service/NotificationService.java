package com.propertypro.service;

import java.util.Map;
import java.util.UUID;

/**
 * Queues SMS / email / in-app messages from the message_templates table
 * (an organisation's own template wins over the platform default).
 * {@link NotificationDispatcher} sends the queue in the background, so a slow
 * SMS provider never slows down a request.
 */
public interface NotificationService {

    enum Channel { SMS, EMAIL, IN_APP }

    enum Recipient { TENANT, OWNER, USER, VENDOR }

    /** Queues a message for the current organisation. Silently skips when there is no address. */
    void queue(Channel channel, Recipient recipientType, UUID recipientId, String to, String templateCode, Map<String, ?> values);

    /** Queues a message whose text is already written (no template). */
    void queueText(Channel channel, Recipient recipientType, UUID recipientId, String to, String subject, String body);

    /** True if this template was already sent to this recipient (used to avoid repeat reminders). */
    boolean alreadySent(String templateCode, UUID recipientId);
}
