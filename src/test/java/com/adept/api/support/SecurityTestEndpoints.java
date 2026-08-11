package com.adept.api.support;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
public final class SecurityTestEndpoints {

    private final AtomicInteger invocations = new AtomicInteger();

    @PostMapping(path = "/api/v1/auth/test-endpoint", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> login(@RequestBody Payload payload) {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/auth/test-me")
    public ResponseEntity<Void> me() {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/workspaces/test-current")
    public ResponseEntity<Void> currentWorkspace() {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(path = "/api/v1/workspaces/test-current", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updateWorkspace(@RequestBody Payload payload) {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/actuator/health/readiness")
    public ResponseEntity<Void> readiness() {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/actuator/info")
    public ResponseEntity<Void> actuatorInfo() {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    @RequestMapping("/api/v1/unlisted")
    public ResponseEntity<Void> unlisted() {
        invocations.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    public int invocations() {
        return invocations.get();
    }

    public void reset() {
        invocations.set(0);
    }

    public record Payload(String value) {
    }
}
