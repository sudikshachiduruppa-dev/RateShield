package com.rateshield.ratelimit.filter;

import com.rateshield.ratelimit.model.TokenBucket;
import com.rateshield.ratelimit.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter applying per-client Token Bucket rate limiting.
 *
 * Scoped specifically to rate-limited paths (/api/ratelimit/**).
 * Requires validated authentication from SecurityContext. Rejects unauthenticated
 * requests with HTTP 401 Unauthorized, never trusting spoofable arbitrary headers.
 * Does not intercept /api/auth/signup or /api/auth/login.
 */
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimiterFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // Only rate-limit endpoints under /api/ratelimit
        return !path.startsWith("/api/ratelimit");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Security enforcement: unauthenticated requests cannot access protected rate-limited endpoints
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication token required to access rate-limited endpoint.\"}");
            return;
        }

        String clientId = authentication.getName();

        // Consume a token from this client's individual bucket
        TokenBucket.ConsumptionResult result = rateLimiterService.consumeClientToken(clientId);

        // Standard RateLimit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.capacity()));

        if (result.allowed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            String jsonPayload = String.format(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for user '%s'. Try again in %d seconds.\",\"retryAfter\":%d}",
                    clientId,
                    result.retryAfterSeconds(),
                    result.retryAfterSeconds()
            );

            response.getWriter().write(jsonPayload);
        }
    }
}
