package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import com.propertypro.dto.TeamDtos.InviteRequest;
import com.propertypro.dto.TeamDtos.InviteResult;
import com.propertypro.dto.TeamDtos.Member;
import com.propertypro.dto.TeamDtos.RoleOption;
import com.propertypro.dto.TeamDtos.RoleRequest;
import com.propertypro.entity.User;
import com.propertypro.repository.OrganizationRepository;
import com.propertypro.repository.UserRepository;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.tenancy.SystemExecutor;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {

    private static final int INVITE_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final PlanGate planGate;
    private final NotificationService notifications;
    private final AppProperties props;
    private final JdbcClient jdbc;
    private final SystemExecutor system;

    @Override
    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Member> list() {
        UUID me = TenantContext.userId().orElse(null);
        return jdbc.sql("""
                        SELECT u.id, u.first_name, u.last_name, u.email::text AS email, u.phone, u.status,
                               u.last_login_at, u.created_at,
                               coalesce(array_agg(r.code ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '{}') AS roles
                          FROM users u
                          LEFT JOIN user_roles ur ON ur.user_id = u.id
                          LEFT JOIN roles r ON r.id = ur.role_id
                         WHERE u.user_type = 'STAFF' AND u.deleted_at IS NULL
                         GROUP BY u.id
                         ORDER BY u.status, u.first_name""")
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String[] roles = (String[]) rs.getArray("roles").getArray();
                    return new Member(id, rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                            rs.getString("phone"), rs.getString("status"), List.of(roles),
                            rs.getObject("last_login_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class), id.equals(me));
                })
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleOption> roles() {
        return jdbc.sql("SELECT code, name, description FROM roles WHERE organization_id IS NULL AND code IN (:codes) ORDER BY name")
                .param("codes", STAFF_ROLES)
                .query((rs, n) -> new RoleOption(rs.getString("code"), rs.getString("name"), rs.getString("description")))
                .list();
    }

    @Override
    // ------------------------------------------------------------------ commands

    @Transactional
    public InviteResult invite(InviteRequest req) {
        planGate.requireWritable();
        String role = checkRole(req.roleCode());
        Integer limit = planGate.currentSubscription().getPlan().getUserLimit();
        long seats = jdbc.sql("SELECT count(*) FROM users WHERE user_type = 'STAFF' AND deleted_at IS NULL AND status <> 'DISABLED'")
                .query(Long.class).single();
        if (limit != null && seats >= limit) {
            throw ApiException.planLimit("USER_LIMIT_REACHED",
                    "Your plan allows " + limit + " team members. Upgrade or disable someone to invite more.");
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        boolean taken = jdbc.sql("SELECT count(*) FROM users WHERE lower(email::text) = ? AND deleted_at IS NULL")
                .param(email).query(Long.class).single() > 0;
        if (taken) {
            throw ApiException.conflict("EMAIL_TAKEN", email + " is already on your team");
        }
        String phone = blankToNull(req.phone());
        // Emails and phones are unique platform-wide; other organisations' users are only visible as the system
        if (system.asSystem(() -> users.emailTaken(email))) {
            throw ApiException.conflict("EMAIL_TAKEN", email + " already has a PropertyPro account with another organisation");
        }
        if (phone != null && system.asSystem(() -> users.phoneTaken(phone))) {
            throw ApiException.conflict("PHONE_TAKEN", phone + " is already used by another PropertyPro account");
        }

        UUID orgId = TenantContext.requireOrganizationId();
        User user = new User();
        user.setOrganizationId(orgId);
        user.setUserType(User.Type.STAFF);
        user.setFirstName(req.firstName().trim());
        user.setLastName(blankToNull(req.lastName()));
        user.setEmail(email);
        user.setPhone(phone);
        user.setStatus(User.Status.INVITED);
        user.setCreatedBy(TenantContext.userId().orElse(null));
        users.saveAndFlush(user);
        if (users.assignRole(user.getId(), orgId, role) != 1) {
            throw new IllegalStateException("System role " + role + " is missing");
        }
        return sendInvite(user, role);
    }

    @Override
    @Transactional
    public InviteResult resendInvite(UUID userId) {
        planGate.requireWritable();
        User user = find(userId);
        if (user.getStatus() != User.Status.INVITED) {
            throw ApiException.conflict("ALREADY_JOINED", user.fullName() + " has already accepted the invitation");
        }
        jdbc.sql("UPDATE user_invitations SET expires_at = now() WHERE user_id = ? AND accepted_at IS NULL AND expires_at > now()")
                .param(userId).update();
        List<String> roles = users.findRoleCodes(userId);
        return sendInvite(user, roles.isEmpty() ? "PROPERTY_MANAGER" : roles.getFirst());
    }

    @Override
    @Transactional
    public Member changeRole(UUID userId, RoleRequest req) {
        planGate.requireWritable();
        String role = checkRole(req.roleCode());
        User user = find(userId);
        if (!"ORG_OWNER".equals(role) && users.findRoleCodes(userId).contains("ORG_OWNER")) {
            requireAnotherOwner(userId);
        }
        jdbc.sql("DELETE FROM user_roles WHERE user_id = ?").param(userId).update();
        users.assignRole(userId, user.getOrganizationId(), role);
        revokeSessions(userId);   // new permissions take effect at next sign-in
        return member(userId);
    }

    @Override
    @Transactional
    public Member disable(UUID userId) {
        planGate.requireWritable();
        if (userId.equals(TenantContext.userId().orElse(null))) {
            throw ApiException.badRequest("CANNOT_DISABLE_SELF", "You cannot disable your own account");
        }
        User user = find(userId);
        if (users.findRoleCodes(userId).contains("ORG_OWNER")) {
            requireAnotherOwner(userId);
        }
        user.setStatus(User.Status.DISABLED);
        revokeSessions(userId);
        jdbc.sql("UPDATE user_invitations SET expires_at = now() WHERE user_id = ? AND accepted_at IS NULL").param(userId).update();
        return member(userId);
    }

    @Override
    @Transactional
    public Member enable(UUID userId) {
        planGate.requireWritable();
        User user = find(userId);
        if (user.getStatus() != User.Status.DISABLED) {
            return member(userId);
        }
        Integer limit = planGate.currentSubscription().getPlan().getUserLimit();
        long seats = jdbc.sql("SELECT count(*) FROM users WHERE user_type = 'STAFF' AND deleted_at IS NULL AND status <> 'DISABLED'")
                .query(Long.class).single();
        if (limit != null && seats >= limit) {
            throw ApiException.planLimit("USER_LIMIT_REACHED", "Your plan allows " + limit + " team members.");
        }
        user.setStatus(user.getPasswordHash() == null ? User.Status.INVITED : User.Status.ACTIVE);
        return member(userId);
    }

    // ------------------------------------------------------------------ helpers

    private InviteResult sendInvite(User user, String role) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expires = OffsetDateTime.now().plusDays(INVITE_DAYS);
        jdbc.sql("""
                        INSERT INTO user_invitations (organization_id, user_id, token_hash, expires_at, created_by)
                        VALUES (?, ?, ?, ?, ?)""")
                .param(user.getOrganizationId()).param(user.getId()).param(AuthServiceImpl.sha256(raw))
                .param(expires).param(TenantContext.userId().orElse(null))
                .update();

        String link = props.web().frontendUrl().replaceAll("/+$", "") + "/accept-invite?token=" + raw;
        String orgName = organizations.findById(user.getOrganizationId()).map(o -> o.getName()).orElse("your team");
        String inviter = TenantContext.userId().flatMap(users::findById).map(User::fullName).orElse("Your admin");
        String roleName = jdbc.sql("SELECT name FROM roles WHERE organization_id IS NULL AND code = ?")
                .param(role).query(String.class).optional().orElse(role);

        Map<String, Object> vars = new HashMap<>();
        vars.put("first_name", user.getFirstName());
        vars.put("inviter_name", inviter);
        vars.put("org_name", orgName);
        vars.put("role_name", roleName);
        vars.put("link", link);
        notifications.queue(Channel.EMAIL, Recipient.USER, user.getId(), user.getEmail(), "STAFF_INVITE", vars);
        notifications.queue(Channel.SMS, Recipient.USER, user.getId(), user.getPhone(), "STAFF_INVITE", vars);
        return new InviteResult(member(user.getId()), link, expires);
    }

    private User find(UUID id) {
        return users.findById(id)
                .filter(u -> u.getDeletedAt() == null && u.getUserType() == User.Type.STAFF)
                .orElseThrow(() -> ApiException.notFound("Team member"));
    }

    private Member member(UUID id) {
        users.flush();
        return list().stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Team member"));
    }

    private void requireAnotherOwner(UUID userId) {
        long others = jdbc.sql("""
                        SELECT count(*) FROM users u
                          JOIN user_roles ur ON ur.user_id = u.id
                          JOIN roles r ON r.id = ur.role_id AND r.code = 'ORG_OWNER'
                         WHERE u.id <> ? AND u.status = 'ACTIVE' AND u.deleted_at IS NULL""")
                .param(userId).query(Long.class).single();
        if (others == 0) {
            throw ApiException.conflict("LAST_OWNER", "Make someone else an owner first; an organisation needs at least one");
        }
    }

    private void revokeSessions(UUID userId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL")
                .param(userId).update();
    }

    private static String checkRole(String code) {
        String role = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!STAFF_ROLES.contains(role)) {
            throw ApiException.badRequest("INVALID_ROLE", "Role must be one of " + String.join(", ", STAFF_ROLES));
        }
        return role;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
