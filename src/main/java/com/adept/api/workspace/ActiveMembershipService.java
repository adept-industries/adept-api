package com.adept.api.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ActiveMembershipService {

    private final MembershipRepository membershipRepository;

    public ActiveMembershipService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public List<Membership> getActiveWorkspaces(UUID userId) {
        return membershipRepository.findAllActiveWithWorkspaceByUserId(userId);
    }

    public Optional<Membership> getActiveMembership(UUID userId, UUID workspaceId) {
        return membershipRepository.findActiveByUserIdAndWorkspaceId(userId, workspaceId);
    }
}
