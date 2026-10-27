package com.propertypro.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.common.ApiError;
import com.propertypro.tenancy.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads "Authorization: Bearer <token>", authenticates the request and sets
 * the {@link TenantContext} for the database. Always clears the context after
 * the request, so a pooled thread never carries a previous organisation.
 * A user who has not confirmed their mobile number yet can only reach
 * /api/v1/auth/** (to get and enter the SMS code); anything else is refused
 * with 403 PHONE_NOT_VERIFIED.
 *
 * <p>Platform (super admin) accounts have no organisation and may only reach
 * /api/v1/auth/**, /api/v1/admin/** and /api/v1/plans (403 PLATFORM_ACCOUNT).
 * Users of a suspended organisation can only reach /api/v1/auth/** (403 ORG_SUSPENDED).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final String AUTH_PATH = "/api/v1/auth/";
    private static final String ADMIN_PATH = "/api/v1/admin/";
    private static final String PLANS_PATH = "/api/v1/plans";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final SuspensionCache suspensions;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            AuthenticatedUser user = header != null && header.startsWith(PREFIX)
                    ? authenticate(header.substring(PREFIX.length()), request) : null;
            String path = request.getRequestURI();
            boolean authPath = path.startsWith(AUTH_PATH);
            if (user != null && !user.phoneVerified() && !authPath) {
                forbid(response, path, "PHONE_NOT_VERIFIED", "Confirm your mobile number with the code we sent by SMS to continue");
                return;
            }
            if (user != null && user.isPlatform() && !authPath && !path.startsWith(ADMIN_PATH)
                    && !path.equals(PLANS_PATH)) {
                // Organisation endpoints need an organisation; a platform admin works through /admin
                forbid(response, path, "PLATFORM_ACCOUNT", "Platform accounts use the admin console (/api/v1/admin)");
                return;
            }
            if (user != null && user.organizationId() != null && !authPath) {
                Optional<String> suspension = suspensions.suspension(user.organizationId());
                if (suspension.isPresent()) {
                    String reason = suspension.get().isBlank() ? "" : " Reason: " + suspension.get();
                    forbid(response, path, "ORG_SUSPENDED",
                            "This account has been suspended. Contact PropertyPro support." + reason);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void forbid(HttpServletResponse response, String path, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(403, code, message, path));
    }

    private AuthenticatedUser authenticate(String token, HttpServletRequest request) {
        AuthenticatedUser user;
        try {
            user = jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null; // invalid or expired: stays anonymous, protected endpoints answer 401
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        user.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));

        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        TenantContext.set(user.organizationId(), user.userId());
        return user;
    }
}
