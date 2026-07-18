package com.adept.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdeptApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdeptApiApplication.class, args);
    }

}
