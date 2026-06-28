package com.pulse.auth.filter;

import com.pulse.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter. When {@code pulse.auth.enabled=true}, this filter
 * extracts the Bearer token from the Authorization header, validates it via
 * {@link JwtService}, and populates the Spring Security context with the
 * authenticated principal.
 *
 * <p>The principal stored in SecurityContext is a {@link JwtPrincipal} record
 * containing userId, email, tenantId, role, and permissions derived from the
 * JWT claims — never from request headers or body.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtService.parseToken(token);
                String userId = claims.getSubject();
                String email = claims.get("email", String.class);
                String displayName = claims.get("displayName", String.class);
                String tenantId = claims.get("tenantId", String.class);
                String role = claims.get("role", String.class);

                @SuppressWarnings("unchecked")
                List<String> permissions = claims.get("permissions", List.class);
                if (permissions == null) {
                    permissions = List.of();
                }

                JwtPrincipal principal = new JwtPrincipal(
                        userId, email, displayName != null ? displayName : email, tenantId, role, permissions);

                List<SimpleGrantedAuthority> authorities = permissions.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // Invalid/expired token — SecurityContext remains empty, rejected by SecurityConfig
                logger.debug("JWT validation failed: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
