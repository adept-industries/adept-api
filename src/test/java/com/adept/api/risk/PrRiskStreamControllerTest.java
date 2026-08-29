package com.adept.api.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrRiskStreamControllerTest {

    @Mock
    private PrRiskSseService sseService;

    private PrRiskStreamController controller;

    @BeforeEach
    void setUp() {
        controller = new PrRiskStreamController(sseService);
    }

    @Test
    void testStreamRiskScoresReturnsEmitter() {
        SseEmitter emitter = new SseEmitter();
        when(sseService.createEmitter()).thenReturn(emitter);

        SseEmitter result = controller.streamRiskScores();

        assertThat(result).isSameAs(emitter);
        verify(sseService).createEmitter();
    }
}
