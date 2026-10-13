package com.rateshield;

import com.rateshield.authentication.jwt.JwtAuthenticationFilter;
import com.rateshield.ratelimit.filter.RateLimiterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimiterFilter rateLimiterFilter;
    private final String allowedOriginsProperty;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimiterFilter rateLimiterFilter,
            @Value("${rateshield.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
            String allowedOriginsProperty
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimiterFilter = rateLimiterFilter;
        this.allowedOriginsProperty = allowedOriginsProperty;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // Stateless: no HTTP session / no JSESSIONID cookies (JWT API)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // Permit all OPTIONS preflight requests globally
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public auth endpoints
                        .requestMatchers(
                                "/api/auth/signup",
                                "/api/auth/login"
                        ).permitAll()
                        // Protected rate-limited endpoint
                        .requestMatchers(
                                "/api/ratelimit/**"
                        ).authenticated()
                        .anyRequest().authenticated()
                )
                // 1. JWT validation filter (runs only inside Spring Security chain)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 2. Rate limiter filter (runs only inside Spring Security chain)
                .addFilterAfter(rateLimiterFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        // Always allow local development origins, plus any deployed origin from environment/properties
        List<String> origins = new ArrayList<>(List.of(
                "http://localhost:5173",
                "http://localhost:5174"
        ));

        if (allowedOriginsProperty != null && !allowedOriginsProperty.isBlank()) {
            for (String origin : allowedOriginsProperty.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !origins.contains(trimmed)) {
                    origins.add(trimmed);
                }
            }
        }

        configuration.setAllowedOrigins(origins);

        // Allow standard HTTP methods
        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "DELETE",
                        "OPTIONS"
                )
        );

        // Allow headers including Content-Type and Authorization
        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Accept",
                        "Origin",
                        "X-Requested-With"
                )
        );

        // Expose rate-limit and auth headers to client
        configuration.setExposedHeaders(
                List.of(
                        "X-RateLimit-Limit",
                        "X-RateLimit-Remaining",
                        "Retry-After",
                        "Authorization"
                )
        );

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L); // Cache preflight for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * Prevents JwtAuthenticationFilter from being auto-registered as a top-level
     * servlet-container filter by Spring Boot's FilterRegistrationAutoConfiguration.
     * It runs exclusively inside the Spring Security filter chain (addFilterBefore above).
     * Without this, the filter executes twice: once at the servlet level and once inside
     * the security chain, causing a second Spring Security pass that produces 403 on public routes.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * Prevents RateLimiterFilter from being auto-registered as a top-level
     * servlet-container filter. It runs exclusively inside the Spring Security filter chain
     * (addFilterAfter above), where shouldNotFilter() correctly scopes it to /api/ratelimit/**.
     */
    @Bean
    public FilterRegistrationBean<RateLimiterFilter> rateLimiterFilterRegistration(
            RateLimiterFilter filter) {
        FilterRegistrationBean<RateLimiterFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
