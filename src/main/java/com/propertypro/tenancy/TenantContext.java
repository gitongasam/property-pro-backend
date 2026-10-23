package com.propertypro.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Who the current request is acting for. Filled from the JWT by
 * {@code JwtAuthenticationFilter}, read by {@link TenantAwareDataSource}
 * (PostgreSQL RLS) and {@link TenantIdentifierResolver} (Hibernate filter).
 *
 * <p>{@code system = true} switches off tenant isolation. Only {@link SystemExecutor}
 * sets it, for sign-up, login, webhooks and cross-organisation jobs.
 */
public final class TenantContext {

    public record State(UUID organizationId, UUID userId, boolean system) {
        static final State EMPTY = new State(null, null, false);
    }

    private static final ThreadLocal<State> CURRENT = ThreadLocal.withInitial(() -> State.EMPTY);

    private TenantContext() {}

    public static void set(UUID organizationId, UUID userId) {
        CURRENT.set(new State(organizationId, userId, false));
    }

    static void setSystem(UUID userId) {
        CURRENT.set(new State(null, userId, true));
    }

    public static Optional<UUID> organizationId() {
        return Optional.ofNullable(CURRENT.get().organizationId());
    }

    /** The current organisation, or an exception if the request has none. */
    public static UUID requireOrganizationId() {
        return organizationId().orElseThrow(
                () -> new IllegalStateException("No organisation in the current request context"));
    }

    public static Optional<UUID> userId() {
        return Optional.ofNullable(CURRENT.get().userId());
    }

    public static boolean isSystem() {
        return CURRENT.get().system();
    }

    public static State snapshot() {
        return CURRENT.get();
    }

    public static void restore(State state) {
        CURRENT.set(state == null ? State.EMPTY : state);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
