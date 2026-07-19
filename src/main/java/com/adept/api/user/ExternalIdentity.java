package com.adept.api.user;

import com.adept.api.common.domain.*;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "external_identities",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"provider", "provider_user_id"}),
           @UniqueConstraint(columnNames = {"user_id", "provider"})
       })
// Links an Adept account to one external provider identity.
public class ExternalIdentity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExternalProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(length = 255)
    private String login;
}
