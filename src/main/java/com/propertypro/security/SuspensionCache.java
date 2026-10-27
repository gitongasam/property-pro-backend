package com.propertypro.security;

import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an organisation is suspended, looked up at most every 30 seconds per
 * organisation so {@link JwtAuthenticationFilter} does not hit the database on every
 * request. Admin actions on this instance call {@link #evict}; other instances
 * catch up within the TTL.
 */
@Component
@RequiredArgsConstructor
public class SuspensionCache {

    private static final Duration TTL = Duration.ofSeconds(30);

    /** {@code reason} is null when the organisation is not suspended. */
    private record Entry(String reason, boolean suspended, Instant expiresAt) {}

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    /** The suspension reason (possibly empty), or empty Optional when the organisation may be used. */
    public Optional<String> suspension(UUID organizationId) {
        Instant now = Instant.now();
        Entry e = cache.get(organizationId);
        if (e == null || now.isAfter(e.expiresAt())) {
            e = load(organizationId, now);
            cache.put(organizationId, e);
        }
        return e.suspended() ? Optional.of(e.reason() == null ? "" : e.reason()) : Optional.empty();
    }

    public void evict(UUID organizationId) {
        cache.remove(organizationId);
    }

    private Entry load(UUID organizationId, Instant now) {
        return system.asSystem(() -> jdbc.sql("SELECT status, suspended_reason FROM organizations WHERE id = ?")
                .param(organizationId)
                .query((rs, n) -> new Entry(rs.getString("suspended_reason"),
                        "SUSPENDED".equals(rs.getString("status")), now.plus(TTL)))
                .optional()
                .orElse(new Entry(null, false, now.plus(TTL))));
    }
}
