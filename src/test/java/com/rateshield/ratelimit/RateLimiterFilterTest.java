package com.rateshield.ratelimit;

import com.rateshield.authentication.jwt.JwtAuthenticationFilter;
import com.rateshield.authentication.jwt.JwtTokenProvider;
import com.rateshield.ratelimit.config.RateLimiterProperties;
import com.rateshield.ratelimit.controller.RateLimitTestController;
import com.rateshield.ratelimit.filter.RateLimiterFilter;
import com.rateshield.ratelimit.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RateLimiterFilterTest {

    private MockMvc mockMvc;
    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setCapacity(5);
        properties.setRefillTokensPerSecond(0.0001); // near-zero refill to test batch of 6 requests

        service = new RateLimiterService(properties);
        RateLimiterFilter filter = new RateLimiterFilter(service);
        RateLimitTestController controller = new RateLimitTestController();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    @Test
    @DisplayName("Permits first 5 requests for authenticated user, returns 429 on 6th request")
    void testFilterEnforcesLimitAndReturns429() throws Exception {
        // Authenticate mock user
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/ratelimit/test"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(4 - i)))
                    .andExpect(jsonPath("$.status").value("ALLOWED"));
        }

        // 6th request must be blocked by RateLimiterFilter with 429
        mockMvc.perform(get("/api/ratelimit/test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.retryAfter").exists());
    }

    @Test
    @DisplayName("Rejects unauthenticated requests with HTTP 401 Unauthorized")
    void testFilterRejectsUnauthenticatedRequests() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/ratelimit/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Non-ratelimited endpoints like auth should not be intercepted")
    void testFilterDoesNotInterceptOtherPaths() throws Exception {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setCapacity(1);
        RateLimiterService service = new RateLimiterService(properties);
        RateLimiterFilter filter = new RateLimiterFilter(service);

        // Path outside /api/ratelimit must bypass filter even if unauthenticated
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/auth/login");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain chain =
                new org.springframework.mock.web.MockFilterChain();

        filter.doFilter(request, response, chain);

        // Filter should not have returned 429 or 401
        org.junit.jupiter.api.Assertions.assertNotEquals(429, response.getStatus());
        org.junit.jupiter.api.Assertions.assertNotEquals(401, response.getStatus());
    }
}
