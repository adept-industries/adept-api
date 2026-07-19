package com.adept.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class AdeptApiApplicationTests {

    @Test
    void declaresSpringBootApplicationEntryPoint() {
        assertThat(AdeptApiApplication.class)
            .hasAnnotation(SpringBootApplication.class);
    }

}
