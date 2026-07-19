package com.adept.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
// Registers the auditing infrastructure used by BaseEntity.
@EnableJpaAuditing
public class JpaAuditConfig {
}
