package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import com.propertypro.dto.AuthDtos.AuthResponse;
import com.propertypro.dto.AuthDtos.ClientInfo;
import com.propertypro.dto.AuthDtos.LoginRequest;
import com.propertypro.dto.AuthDtos.MeResponse;
import com.propertypro.dto.AuthDtos.OrganizationSummary;
import com.propertypro.dto.AuthDtos.PlanSummary;
import com.propertypro.dto.AuthDtos.SignupRequest;
import com.propertypro.entity.Organization;
import com.propertypro.entity.Plan;
import com.propertypro.entity.RefreshToken;
import com.propertypro.entity.Subscription;
import com.propertypro.entity.User;
import com.propertypro.repository.OrganizationRepository;
import com.propertypro.repository.PlanRepository;
import com.propertypro.repository.RefreshTokenRepository;
import com.propertypro.repository.SubscriptionRepository;
import com.propertypro.repository.UserRepository;
import com.propertypro.security.AuthenticatedUser;
import com.propertypro.security.JwtService;
import com.propertypro.tenancy.SystemExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SystemExecutor system;
    private final OrganizationRepository organizations;
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final PhoneVerificationService phoneVerification;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthServiceImpl(SystemExecutor system, OrganizationRepository organizations, PlanRepository plans,
                           SubscriptionRepository subscriptions, UserRepository users,
                           RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                           JwtService jwtService, JdbcTemplate jdbc, AppProperties props,
                           PhoneVerificationService phoneVerification) {
        this.system = system;
        this.organizations = organizations;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jdbc = jdbc;
        this.props = props;
        this.phoneVerification = phoneVerification;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    // ------------------------------------------------------------------
    // Sign-up: new organisation + trial subscription + owner user
    // ------------------------------------------------------------------
    public AuthResponse signup(SignupRequest req, ClientInfo client) {
        String passwordHash = passwordEncoder.encode(req.password());

        User owner = system.asSystem(() -> {
            if (users.emailTaken(req.email().trim())) {
                throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists. Sign in instead.");
            }
            if (users.phoneTaken(req.phone())) {
                throw ApiException.conflict("PHONE_TAKEN", "This phone number is already used by another account");
            }
            Plan trialPlan = plans.findByCode(props.signup().trialPlan())
                    .orElseThrow(() -> new IllegalStateException("Trial plan not found: " + props.signup().trialPlan()));

            Organization org = new Organization();
            org.setName(req.organizationName().trim());
            org.setSlug(uniqueSlug(req.organizationName()));
            org.setEmail(req.email().trim().toLowerCase(Locale.ROOT));
            org.setPhone(req.phone());
            org.setCounty(req.county());
            org.setTown(req.town());
            org.setStatus(Organization.Status.TRIAL);
            organizations.saveAndFlush(org);

            int trialDays = props.signup().trialDays();
            Subscription sub = new Subscription();
            sub.setOrganizationId(org.getId());
            sub.setPlan(trialPlan);
            sub.setStatus(Subscription.Status.TRIALING);
            sub.setPrice(trialPlan.getMonthlyPrice());
            sub.setCurrentPeriodStart(LocalDate.now());
            sub.setCurrentPeriodEnd(LocalDate.now().plusDays(trialDays));
            sub.setTrialEndsAt(OffsetDateTime.now().plusDays(trialDays));
            subscriptions.save(sub);

            User user = new User();
            user.setOrganizationId(org.getId());
            user.setUserType(User.Type.STAFF);
            user.setFirstName(req.firstName().trim());
            user.setLastName(req.lastName() == null ? null : req.lastName().trim());
            user.setEmail(req.email());
            user.setPhone(req.phone());
            user.setPasswordHash(passwordHash);
            user.setStatus(User.Status.ACTIVE);
            if (!props.signup().phoneVerification()) {
                user.setPhoneVerifiedAt(OffsetDateTime.now());
            }
            users.saveAndFlush(user);

            if (users.assignRole(user.getId(), org.getId(), OWNER_ROLE) != 1) {
                throw new IllegalStateException("System role " + OWNER_ROLE + " is missing");
            }
            jdbc.queryForList("SELECT create_organization_defaults(?)", org.getId());
            jdbc.update("INSERT INTO onboarding_progress (organization_id) VALUES (?) ON CONFLICT DO NOTHING", org.getId());

            log.info("New organisation {} ({}) signed up on a {}-day {} trial",
                    org.getName(), org.getId(), trialDays, trialPlan.getCode());
            return user;
        });

        if (owner.getPhoneVerifiedAt() == null) {
            try {
                phoneVerification.sendCode(owner.getId());
            } catch (ApiException e) {
                // The account exists either way; the confirmation screen can ask for another code
                log.warn("Could not send the confirmation code at sign-up for user {}: {}", owner.getId(), e.getMessage());
            }
        }
        return issueTokens(owner, client);
    }

    @Override
    // ------------------------------------------------------------------
    // Confirm the mobile number with the SMS code
    // ------------------------------------------------------------------
    public AuthResponse verifyPhone(AuthenticatedUser principal, String code, ClientInfo client) {
        phoneVerification.verify(principal.userId(), code);
        User user = system.asSystem(() -> users.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("Account not found")));
        return issueTokens(user, client);     // new access token carries the confirmed flag
    }

    @Override
    // ------------------------------------------------------------------
    // Login with email + password
    // ------------------------------------------------------------------
    public AuthResponse login(LoginRequest req, ClientInfo client) {
        String email = req.email().trim();
        List<User> candidates = system.asSystem(() -> users.findLoginCandidates(email));

        if (candidates.isEmpty()) {
            passwordEncoder.matches(req.password(), dummyHash);
            throw invalidCredentials();
        }

        List<User> matching = candidates.stream()
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .toList();

        if (req.organizationSlug() != null && !req.organizationSlug().isBlank()) {
            UUID orgId = system.asSystem(() -> organizations.findBySlug(req.organizationSlug().trim())
                    .map(Organization::getId).orElse(null));
            matching = matching.stream().filter(u -> Objects.equals(u.getOrganizationId(), orgId)).toList();
        }

        if (matching.isEmpty()) {
            throw invalidCredentials();
        }
        if (matching.size() > 1) {
            List<UUID> orgIds = matching.stream().map(User::getOrganizationId).filter(Objects::nonNull).toList();
            String slugs = system.asSystem(() -> organizations.findAllById(orgIds).stream()
                    .map(Organization::getSlug).sorted().collect(Collectors.joining(", ")));
            throw ApiException.conflict("ORGANIZATION_REQUIRED",
                    "This email belongs to more than one organisation. Choose one: " + slugs);
        }

        User user = matching.getFirst();
        system.asSystem(() -> {
            users.findById(user.getId()).ifPresent(u -> u.setLastLoginAt(OffsetDateTime.now()));
            return null;
        });
        return issueTokens(user, client);
    }

    @Override
    // ------------------------------------------------------------------
    // Refresh: rotate the refresh token, issue a new access token
    // ------------------------------------------------------------------
    public AuthResponse refresh(String rawRefreshToken, ClientInfo client) {
        String hash = sha256(rawRefreshToken);
        OffsetDateTime now = OffsetDateTime.now();

        RefreshToken stored = system.asSystem(() -> refreshTokens.findByTokenHash(hash).orElse(null));
        if (stored == null) {
            throw ApiException.unauthorized("Your session has expired. Sign in again.");
        }
        if (stored.getRevokedAt() != null) {
            // A token used after it was rotated means it may have been stolen: end every session.
            log.warn("Refresh token reuse detected for user {}; revoking all sessions", stored.getUserId());
            system.asSystem(() -> refreshTokens.revokeAllForUser(stored.getUserId(), now));
            throw ApiException.unauthorized("Your session has expired. Sign in again.");
        }
        if (!stored.isUsable(now)) {
            throw ApiException.unauthorized("Your session has expired. Sign in again.");
        }

        User user = system.asSystem(() -> users.findById(stored.getUserId())
                .filter(u -> u.getDeletedAt() == null && u.getStatus() == User.Status.ACTIVE)
                .orElse(null));
        if (user == null) {
            throw ApiException.unauthorized("Your account is no longer active.");
        }

        IssuedToken replacement = system.asSystem(() -> {
            RefreshToken locked = refreshTokens.lockById(stored.getId()).orElse(null);
            if (locked == null || locked.getRevokedAt() != null) {
                return null;   // another request rotated it first
            }
            IssuedToken issued = saveNewRefreshToken(user.getId(), client);
            locked.setRevokedAt(now);
            locked.setReplacedBy(issued.entity().getId());
            return issued;
        });
        if (replacement == null) {
            throw ApiException.unauthorized("Your session has expired. Sign in again.");
        }
        return buildResponse(user, replacement.raw());
    }

    @Override
    // ------------------------------------------------------------------
    // Logout: revoke the refresh token (the access token expires on its own)
    // ------------------------------------------------------------------
    public void logout(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        system.asSystem(() -> {
            refreshTokens.findByTokenHash(hash)
                    .filter(t -> t.getRevokedAt() == null)
                    .ifPresent(t -> t.setRevokedAt(OffsetDateTime.now()));
            return null;
        });
    }

    @Override
    // ------------------------------------------------------------------
    // Accept a staff invitation: set a password, activate, sign in
    // ------------------------------------------------------------------
    public AuthResponse acceptInvite(String rawToken, String password, ClientInfo client) {
        String hash = sha256(rawToken.trim());
        String passwordHash = passwordEncoder.encode(password);
        User user = system.asSystem(() -> {
            var rows = jdbc.queryForList("""
                    SELECT id, user_id FROM user_invitations
                     WHERE token_hash = ? AND accepted_at IS NULL AND expires_at > now()
                     FOR UPDATE""", hash);
            if (rows.isEmpty()) {
                throw ApiException.badRequest("INVITE_INVALID",
                        "This invitation link has expired or was already used. Ask your admin to send a new one.");
            }
            UUID invitationId = (UUID) rows.getFirst().get("id");
            UUID userId = (UUID) rows.getFirst().get("user_id");
            User u = users.findById(userId)
                    .filter(x -> x.getDeletedAt() == null && x.getStatus() == User.Status.INVITED)
                    .orElseThrow(() -> ApiException.badRequest("INVITE_INVALID", "This invitation is no longer valid"));
            u.setPasswordHash(passwordHash);
            u.setStatus(User.Status.ACTIVE);
            u.setEmailVerifiedAt(OffsetDateTime.now());
            u.setLastLoginAt(OffsetDateTime.now());
            jdbc.update("UPDATE user_invitations SET accepted_at = now() WHERE id = ?", invitationId);
            return users.saveAndFlush(u);
        });
        return issueTokens(user, client);
    }

    @Override
    // ------------------------------------------------------------------
    // Change password (signed in): check the current one, end other sessions
    // ------------------------------------------------------------------
    public AuthResponse changePassword(AuthenticatedUser principal, String currentPassword, String newPassword,
                                       ClientInfo client) {
        User user = system.asSystem(() -> users.findById(principal.userId())
                .filter(u -> u.getDeletedAt() == null && u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> ApiException.unauthorized("Account not found")));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "Your current password is not right");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("SAME_PASSWORD", "Choose a password different from the current one");
        }
        String newHash = passwordEncoder.encode(newPassword);
        system.asSystem(() -> {
            jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", newHash, user.getId());
            refreshTokens.revokeAllForUser(user.getId(), OffsetDateTime.now());
            jdbc.update("UPDATE password_reset_tokens SET expires_at = now() WHERE user_id = ? AND used_at IS NULL AND expires_at > now()",
                    user.getId());
            return null;
        });
        log.info("User {} changed their password; other sessions signed out", user.getId());
        return issueTokens(user, client);
    }

    @Override
    // ------------------------------------------------------------------
    // Current user profile, organisation and plan
    // ------------------------------------------------------------------
    public MeResponse me(AuthenticatedUser principal) {
        return system.asSystem(() -> buildMe(principal));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    /** A refresh token row plus the raw value, which is returned to the client once and never stored. */
    private record IssuedToken(RefreshToken entity, String raw) {}

    private AuthResponse issueTokens(User user, ClientInfo client) {
        IssuedToken issued = system.asSystem(() -> saveNewRefreshToken(user.getId(), client));
        return buildResponse(user, issued.raw());
    }

    private AuthResponse buildResponse(User user, String rawRefreshToken) {
        AuthenticatedUser principal = system.asSystem(() -> new AuthenticatedUser(
                user.getId(),
                user.getOrganizationId(),
                user.getUserType().name(),
                users.findRoleCodes(user.getId()),
                users.findPermissionCodes(user.getId()),
                phoneVerified(user)));
        String accessToken = jwtService.issueAccessToken(principal);
        MeResponse me = system.asSystem(() -> buildMe(principal));
        return new AuthResponse(accessToken, rawRefreshToken, "Bearer", jwtService.accessTokenSeconds(), me);
    }

    private IssuedToken saveNewRefreshToken(UUID userId, ClientInfo client) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(sha256(raw));
        token.setExpiresAt(OffsetDateTime.now().plus(Duration.ofDays(props.jwt().refreshTokenDays())));
        if (client != null) {
            token.setUserAgent(truncate(client.userAgent(), 500));
            token.setIpAddress(client.ipAddress());
        }
        return new IssuedToken(refreshTokens.save(token), raw);
    }

    private MeResponse buildMe(AuthenticatedUser principal) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("Account not found"));

        OrganizationSummary org = null;
        PlanSummary plan = null;
        if (principal.organizationId() != null) {
            org = organizations.findById(principal.organizationId())
                    .map(o -> new OrganizationSummary(o.getId(), o.getName(), o.getSlug(), o.getStatus().name()))
                    .orElse(null);
            plan = subscriptions.findFirstByOrganizationIdAndStatusIn(principal.organizationId(), Subscription.LIVE)
                    .map(s -> new PlanSummary(
                            s.getPlan().getCode(), s.getPlan().getName(), s.getStatus().name(), s.getTrialEndsAt(),
                            s.getPlan().getUnitLimit(), s.getPlan().getUserLimit(), s.getPlan().getFeatures()))
                    .orElse(null);
        }
        return new MeResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail(), user.getPhone(),
                user.getUserType().name(), principal.roles(), principal.permissions(), org, plan, phoneVerified(user));
    }

    /** A user without a phone has nothing to confirm. */
    private static boolean phoneVerified(User user) {
        return user.getPhone() == null || user.getPhoneVerifiedAt() != null;
    }

    private String uniqueSlug(String organizationName) {
        String base = Normalizer.normalize(organizationName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "org";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-+$", "");
        }
        String candidate = base;
        for (int attempt = 0; organizations.slugExists(candidate); attempt++) {
            if (attempt >= 10) {
                throw ApiException.conflict("SLUG_UNAVAILABLE", "Could not reserve a name for this organisation");
            }
            candidate = base + "-" + HexFormat.of().formatHex(new byte[]{(byte) RANDOM.nextInt(), (byte) RANDOM.nextInt()});
        }
        return candidate;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
