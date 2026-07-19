package com.adept.api.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
// Not a table by itself. Its mapped fields are inherited by concrete entities.
@MappedSuperclass
// Calls the auditing listener before insert/update so timestamps are populated.
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    // Internal primary key generated as a UUID for each new entity.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Set once when the row is first inserted.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Refreshed whenever Hibernate updates the row.
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Hibernate includes this value in UPDATE checks to detect lost updates.
    @Version
    @Column(nullable = false)
    private long version;
}
