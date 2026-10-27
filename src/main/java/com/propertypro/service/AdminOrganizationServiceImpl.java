package com.propertypro.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.common.ApiException;
import com.propertypro.dto.AdminDtos.AdminAction;
import com.propertypro.dto.AdminDtos.OrganizationDetail;
import com.propertypro.dto.AdminDtos.OrganizationRow;
import com.propertypro.dto.AdminDtos.StaffUser;
import com.propertypro.dto.AdminDtos.SubscriptionInfo;
import com.propertypro.dto.AdminDtos.Usage;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Organization;
import com.propertypro.entity.Subscription;
import com.propertypro.repository.OrganizationRepository;
import com.propertypro.repository.SubscriptionRepository;
import com.propertypro.security.AuthenticatedUser;
import com.propertypro.security.SuspensionCache;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Not @Transactional on purpose: a request from a platform user has no organisation, so
 * an outer transaction would hold a connection that sees nothing. Every method does its
 * work inside {@link SystemExecutor#asSystem}, which opens its own transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrganizationServiceImpl implements AdminOrganizationService {

    private static final Set<String> STATUSES = Set.of("TRIAL", "ACTIVE", "PAST_DUE", "SUSPENDED", "CANCELLED");
    private static final int MAX_PAGE_SIZE = 100;

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final OrganizationRepository organizations;
    private final SubscriptionRepository subscriptions;
    private final SuspensionCache suspensions;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ queries

    @Override
    public PageResponse<OrganizationRow> list(String query, String status, String planCode, int page, int size) {
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder(" WHERE o.deleted_at IS NULL");
        Map<String, Object> params = new HashMap<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND (o.name ILIKE :like OR o.slug::text ILIKE :like OR o.email::text ILIKE :like OR o.phone LIKE :like)");
            params.put("like", "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(s)) {
                throw ApiException.badRequest("INVALID_STATUS", "Status must be one of " + String.join(", ", STATUSES));
            }
            where.append(" AND o.status = :status");
            params.put("status", s);
        }
        if (planCode != null && !planCode.isBlank()) {
            where.append(" AND p.code = :plan");
            params.put("plan", planCode.trim().toUpperCase(Locale.ROOT));
        }
        String from = """
                 FROM organizations o
                 LEFT JOIN subscriptions s ON s.organization_id = o.id AND s.status IN ('TRIALING','ACTIVE','PAST_DUE')
                 LEFT JOIN plans p ON p.id = s.plan_id""" + where;

        return system.asSystem(() -> {
            long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
            List<OrganizationRow> rows = jdbc.sql("""
                            SELECT o.id, o.name, o.slug::text AS slug, o.email::text AS email, o.phone, o.status,
                                   p.code AS plan_code, s.status AS sub_status, s.trial_ends_at, s.current_period_end,
                                   o.created_at,
                                   (SELECT count(*) FROM units u
                                     WHERE u.organization_id = o.id AND u.deleted_at IS NULL) AS units,
                                   (SELECT count(*) FROM users x
                                     WHERE x.organization_id = o.id AND x.user_type = 'STAFF' AND x.deleted_at IS NULL) AS users
                            """ + from + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset")
                    .params(params)
                    .param("limit", pageSize)
                    .param("offset", (long) pageNo * pageSize)
                    .query((rs, n) -> new OrganizationRow(
                            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                            rs.getString("email"), rs.getString("phone"), rs.getString("status"),
                            rs.getString("plan_code"), rs.getString("sub_status"),
                            rs.getObject("trial_ends_at", OffsetDateTime.class),
                            rs.getObject("current_period_end", LocalDate.class),
                            rs.getLong("units"), rs.getLong("users"),
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .list();
            int totalPages = (int) ((total + pageSize - 1) / pageSize);
            return new PageResponse<>(rows, pageNo, pageSize, total, totalPages);
        });
    }

    @Override
    public OrganizationDetail get(UUID organizationId) {
        return system.asSystem(() -> detail(find(organizationId)));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public OrganizationDetail suspend(UUID organizationId, String reason) {
        OrganizationDetail result = system.asSystem(() -> {
            Organization org = find(organizationId);
            if (org.getStatus() == Organization.Status.SUSPENDED) {
                throw ApiException.conflict("ALREADY_SUSPENDED", org.getName() + " is already suspended");
            }
            String previous = org.getStatus().name();
            org.setStatus(Organization.Status.SUSPENDED);
            org.setSuspendedAt(OffsetDateTime.now());
            org.setSuspendedReason(reason.trim());
            organizations.saveAndFlush(org);
            record(org.getId(), "SUSPEND", reason, Map.of("previousStatus", previous));
            return detail(org);
        });
        suspensions.evict(organizationId);
        log.warn("Organisation {} suspended by admin {}: {}", organizationId, adminId(), reason);
        return result;
    }

    @Override
    public OrganizationDetail reactivate(UUID organizationId, String reason) {
        OrganizationDetail result = system.asSystem(() -> {
            Organization org = find(organizationId);
            if (org.getStatus() != Organization.Status.SUSPENDED) {
                throw ApiException.conflict("NOT_SUSPENDED", org.getName() + " is not suspended");
            }
            Organization.Status restored = subscriptions
                    .findFirstByOrganizationIdAndStatusIn(org.getId(), Subscription.LIVE)
                    .map(s -> switch (s.getStatus()) {
                        case TRIALING -> Organization.Status.TRIAL;
                        case PAST_DUE -> Organization.Status.PAST_DUE;
                        default -> Organization.Status.ACTIVE;
                    })
                    .orElse(Organization.Status.CANCELLED);
            org.setStatus(restored);
            org.setSuspendedAt(null);
            org.setSuspendedReason(null);
            organizations.saveAndFlush(org);
            record(org.getId(), "REACTIVATE", reason, Map.of("newStatus", restored.name()));
            return detail(org);
        });
        suspensions.evict(organizationId);
        log.info("Organisation {} reactivated by admin {}", organizationId, adminId());
        return result;
    }

    @Override
    public OrganizationDetail extendTrial(UUID organizationId, int days, String reason) {
        return system.asSystem(() -> {
            Organization org = find(organizationId);
            Subscription sub = subscriptions.findFirstByOrganizationIdAndStatusIn(org.getId(), Subscription.LIVE)
                    .orElseThrow(() -> ApiException.conflict("NO_SUBSCRIPTION", org.getName() + " has no live subscription"));
            if (sub.getStatus() != Subscription.Status.TRIALING) {
                throw ApiException.conflict("NOT_ON_TRIAL",
                        org.getName() + " is not on a trial (subscription is " + sub.getStatus() + ")");
            }
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime previousEnd = sub.getTrialEndsAt();
            OffsetDateTime base = previousEnd == null || previousEnd.isBefore(now) ? now : previousEnd;
            OffsetDateTime newEnd = base.plusDays(days);
            sub.setTrialEndsAt(newEnd);
            sub.setCurrentPeriodEnd(newEnd.toLocalDate());
            subscriptions.saveAndFlush(sub);

            Map<String, Object> payload = new HashMap<>();
            payload.put("days", days);
            payload.put("previousTrialEndsAt", previousEnd == null ? null : previousEnd.toString());
            payload.put("trialEndsAt", newEnd.toString());
            record(org.getId(), "EXTEND_TRIAL", reason, payload);
            return detail(org);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Organization find(UUID id) {
        return organizations.findById(id)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Organisation"));
    }

    /** Runs inside asSystem. */
    private OrganizationDetail detail(Organization o) {
        UUID id = o.getId();
        Subscription sub = subscriptions.findFirstByOrganizationIdAndStatusIn(id, Subscription.LIVE)
                .or(() -> subscriptions.findFirstByOrganizationIdOrderByCreatedAtDesc(id))
                .orElse(null);
        SubscriptionInfo subInfo = sub == null ? null : new SubscriptionInfo(
                sub.getId(), sub.getPlan().getCode(), sub.getPlan().getName(), sub.getStatus().name(),
                sub.getBillingCycle().name(), sub.getPrice(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(),
                sub.getTrialEndsAt(), sub.getGraceEndsAt(), sub.allowsWrites(OffsetDateTime.now()));

        Usage usage = jdbc.sql("""
                        SELECT (SELECT count(*) FROM properties WHERE organization_id = :id AND deleted_at IS NULL) AS properties,
                               (SELECT count(*) FROM units      WHERE organization_id = :id AND deleted_at IS NULL) AS units,
                               (SELECT count(*) FROM tenants    WHERE organization_id = :id AND deleted_at IS NULL) AS tenants,
                               (SELECT count(*) FROM leases     WHERE organization_id = :id AND deleted_at IS NULL
                                                                  AND status IN ('ACTIVE','NOTICE_GIVEN')) AS leases,
                               (SELECT count(*) FROM users      WHERE organization_id = :id AND deleted_at IS NULL
                                                                  AND user_type = 'STAFF' AND status <> 'DISABLED') AS staff""")
                .param("id", id)
                .query((rs, n) -> new Usage(rs.getLong("properties"), rs.getLong("units"),
                        sub == null ? null : sub.getPlan().getUnitLimit(), rs.getLong("tenants"), rs.getLong("leases"),
                        rs.getLong("staff"), sub == null ? null : sub.getPlan().getUserLimit()))
                .single();

        List<StaffUser> staff = jdbc.sql("""
                        SELECT u.id, u.first_name, u.last_name, u.email::text AS email, u.phone, u.status, u.last_login_at,
                               coalesce(array_agg(r.code ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '{}') AS roles
                          FROM users u
                          LEFT JOIN user_roles ur ON ur.user_id = u.id
                          LEFT JOIN roles r ON r.id = ur.role_id
                         WHERE u.organization_id = ? AND u.user_type = 'STAFF' AND u.deleted_at IS NULL
                         GROUP BY u.id
                         ORDER BY u.status, u.first_name""")
                .param(id)
                .query((rs, n) -> {
                    String last = rs.getString("last_name");
                    String name = last == null || last.isBlank() ? rs.getString("first_name") : rs.getString("first_name") + " " + last;
                    return new StaffUser(rs.getObject("id", UUID.class), name, rs.getString("email"), rs.getString("phone"),
                            rs.getString("status"), List.of((String[]) rs.getArray("roles").getArray()),
                            rs.getObject("last_login_at", OffsetDateTime.class));
                })
                .list();
        OffsetDateTime lastActivity = staff.stream().map(StaffUser::lastLoginAt).filter(t -> t != null)
                .max(OffsetDateTime::compareTo).orElse(null);

        List<AdminAction> actions = jdbc.sql("""
                        SELECT a.id, a.action, a.reason, a.payload::text AS payload, a.created_at,
                               trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS admin_name
                          FROM admin_actions a
                          JOIN users u ON u.id = a.admin_user_id
                         WHERE a.organization_id = ?
                         ORDER BY a.created_at DESC
                         LIMIT 20""")
                .param(id)
                .query((rs, n) -> new AdminAction(rs.getLong("id"), rs.getString("action"), rs.getString("reason"),
                        readPayload(rs.getString("payload")), rs.getString("admin_name"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        return new OrganizationDetail(id, o.getName(), o.getSlug(), o.getLegalName(), o.getEmail(), o.getPhone(),
                o.getCounty(), o.getTown(), o.getStatus().name(), o.getSuspendedAt(), o.getSuspendedReason(),
                o.getCreatedAt(), lastActivity, subInfo, usage, staff, actions);
    }

    /** Runs inside asSystem (admin_actions is only visible with the RLS bypass). */
    private void record(UUID organizationId, String action, String reason, Map<String, Object> payload) {
        jdbc.sql("""
                        INSERT INTO admin_actions (admin_user_id, organization_id, action, reason, payload)
                        VALUES (?, ?, ?, ?, ?::jsonb)""")
                .param(adminId()).param(organizationId).param(action)
                .param(reason == null || reason.isBlank() ? null : reason.trim())
                .param(writePayload(payload))
                .update();
    }

    private static UUID adminId() {
        return AuthenticatedUser.current().userId();
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readPayload(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
