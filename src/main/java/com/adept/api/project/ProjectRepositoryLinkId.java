package com.adept.api.project;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Embeddable
public class ProjectRepositoryLinkId implements Serializable {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "repository_id")
    private UUID repositoryId;
}
