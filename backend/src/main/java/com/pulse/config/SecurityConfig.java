package com.pulse.config;

import com.pulse.auth.filter.JwtAuthenticationFilter;
import com.pulse.auth.filter.TenantMembershipFilter;
import com.pulse.auth.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${pulse.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${pulse.auth.enabled:false}")
    private boolean authEnabled;

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable());

        if (authEnabled) {
            JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
            TenantMembershipFilter tenantFilter = new TenantMembershipFilter();
            http
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, JwtAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/v1/auth/login").permitAll()
                    .requestMatchers("/ws/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll()
                );
        } else {
            // Dev/test mode (pulse.auth.enabled=false): all endpoints open,
            // AND the anonymous authentication is granted the full set of
            // roles that @PreAuthorize annotations check throughout the
            // codebase. Without this, @PreAuthorize("hasRole('ADMIN')") on
            // controllers like RuntimeBindingController fires
            // AuthorizationDeniedException for the dev stub user (whose
            // /auth/me JSON says DATA_ENGINEER), making the UI's Save
            // buttons silently 403. Session-3 (2026-05-26) operator
            // mandate: dev stub must be fully permissive so UI flows can
            // be exercised end-to-end without needing real JWT.
            //
            // ROLE_* prefix is the Spring Security convention that
            // hasRole('X') strips before comparing.
            // AnonymousConfigurer.authorities() takes String[] (not
            // GrantedAuthority[]). Spring constructs SimpleGrantedAuthority
            // from each string and strips the ROLE_ prefix when hasRole(...)
            // is evaluated.
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .anonymous(anon -> anon.authorities(
                    "ROLE_CITIZEN",
                    "ROLE_DATA_ENGINEER",
                    "ROLE_DEPLOYER",
                    "ROLE_ADMIN",
                    "ROLE_TENANT_USER",
                    "ROLE_PIPELINE_DEVELOPER",
                    "ROLE_DEPLOYMENT_OPERATOR",
                    "ROLE_PULL_REQUEST_APPROVER",
                    "ROLE_TENANT_ADMIN",
                    "ROLE_PLATFORM_ADMIN"
                ));
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
