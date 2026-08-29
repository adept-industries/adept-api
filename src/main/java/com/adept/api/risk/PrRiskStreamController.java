package com.adept.api.risk;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
@RequestMapping
public class PrRiskStreamController {

    private final PrRiskSseService sseService;

    public PrRiskStreamController(PrRiskSseService sseService) {
        this.sseService = sseService;
    }

    /**
     * Real-time Server-Sent Events (SSE) stream endpoint for frontend clients.
     * Connected clients receive 'pr-risk' events whenever a GitHub PR webhook is evaluated.
     */
    @GetMapping(value = {"/api/pr-risk/stream", "/api/v1/pr-risk/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRiskScores() {
        return sseService.createEmitter();
    }
}
