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

/**
 * Reads "Authorization: Bearer <token>", authenticates the request and sets
 * the {@link TenantContext} for the database. Always clears the context after
 * the request, so a pooled thread never carries a previous organisation.
 * A user who has not confirmed their mobile number yet can only reach
 * /api/v1/auth/** (to get and enter the SMS code); anything else is refused
 * with 403 PHONE_NOT_VERIFIED.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            AuthenticatedUser user = header != null && header.startsWith(PREFIX)
                    ? authenticate(header.substring(PREFIX.length()), request) : null;
            if (user != null && !user.phoneVerified() && !request.getRequestURI().startsWith("/api/v1/auth/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), ApiError.of(403, "PHONE_NOT_VERIFIED",
                        "Confirm your mobile number with the code we sent by SMS to continue", request.getRequestURI()));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
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
