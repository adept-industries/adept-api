package com.adept.api.risk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.adept.api.risk.dto.PrRiskBroadcastEvent;

@Service
public class PrRiskSseService {

    private static final Logger log = LoggerFactory.getLogger(PrRiskSseService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter createEmitter() {
        // 30-minute timeout for real-time SSE stream
        SseEmitter emitter = new SseEmitter(1800_000L);
        emitters.add(emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed");
            emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out");
            emitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE emitter error: {}", e.getMessage());
            emitters.remove(emitter);
        });

        // Send initial handshake connect event
        try {
            emitter.send(SseEmitter.event()
                .name("connect")
                .data(Map.of("message", "connected to PR risk stream", "activeClients", emitters.size())));
        } catch (IOException e) {
            log.warn("Failed to send initial connect event to SSE client: {}", e.getMessage());
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void broadcast(PrRiskBroadcastEvent event) {
        log.info("Broadcasting PR risk score to {} connected client(s): prTitle='{}', riskScore={}, riskLevel={}",
            emitters.size(), event.prTitle(), event.riskScore(), event.riskLevel());

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("pr_risk_score")
                    .data(event));
            } catch (Exception e) {
                log.debug("Removing dead SSE emitter: {}", e.getMessage());
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    public int getActiveClientCount() {
        return emitters.size();
    }
}
