package com.adept.api.workspace;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.common.domain.WorkspaceStatus;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "workspaces")
// One instance is one tenant boundary with its display timezone and status.
public class Workspace extends BaseEntity {
    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkspaceStatus status = WorkspaceStatus.ACTIVE;
}
