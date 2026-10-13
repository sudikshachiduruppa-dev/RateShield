package com.rateshield.ratelimit.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/ratelimit")
public class RateLimitTestController {

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testEndpoint() {
        return ResponseEntity.ok(Map.of(
                "status", "ALLOWED",
                "message", "Request permitted by RateShield",
                "timestamp", Instant.now().toString()
        ));
    }
}
