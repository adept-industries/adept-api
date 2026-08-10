package com.adept.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.dto.AuthSessionResponse;
import com.adept.api.auth.dto.MembershipSummary;
import com.adept.api.auth.dto.UserSummary;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

@Service
public class RefreshTokenService {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final ActiveMembershipService activeMembershipService;
    private final TokenHasher tokenHasher;
    private final SecureTokenGenerator secureTokenGenerator;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            ActiveMembershipService activeMembershipService,
            TokenHasher tokenHasher,
            SecureTokenGenerator secureTokenGenerator,
            JwtService jwtService,
            AuditService auditService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.activeMembershipService = activeMembershipService;
        this.tokenHasher = tokenHasher;
        this.secureTokenGenerator = secureTokenGenerator;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public sealed interface RefreshOutcome {
        record Success(
            AuthSessionResponse response,
            String rawChildRefreshToken,
            Instant refreshExpiresAt
        ) implements RefreshOutcome {}

        record Invalid() implements RefreshOutcome {}
        record ReuseDetected() implements RefreshOutcome {}
        record IneligibleUser() implements RefreshOutcome {}
        record NoActiveMembership() implements RefreshOutcome {}
    }

    public sealed interface SwitchOutcome {
        record Success(AuthSessionResponse response) implements SwitchOutcome {}
        record WorkspaceNotFound() implements SwitchOutcome {}
        record Invalid() implements SwitchOutcome {}
        record ReuseDetected() implements SwitchOutcome {}
        record IneligibleUser() implements SwitchOutcome {}
    }

    public RefreshOutcome rotate(String rawCookieValue, UUID requestedWorkspaceId, AccountRequestContext context) {
        if (!SecureTokenGenerator.isWellFormed(rawCookieValue)) {
            return new RefreshOutcome.Invalid();
        }

        String tokenHash = tokenHasher.hashRefreshToken(rawCookieValue);

        Optional<RefreshTokenProjection> projection = refreshTokenRepository.findIdAndUserIdByTokenHash(tokenHash);
        if (projection.isEmpty()) {
            return new RefreshOutcome.Invalid();
        }

        UUID tokenId = projection.get().getId();
        UUID userId = projection.get().getUserId();

        return transactionTemplate.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) {
                return new RefreshOutcome.Invalid();
            }

            RefreshToken token = refreshTokenRepository.findByIdForUpdate(tokenId).orElse(null);
            if (token == null || !token.getUser().getId().equals(user.getId())) {
                return new RefreshOutcome.Invalid();
            }

            Instant now = clock.instant();

            if (!token.getExpiresAt().isAfter(now)) {
                return new RefreshOutcome.Invalid();
            }

            if (token.getRotatedAt() != null) {
                commitReuseDetection(user, token, now, context);
                return new RefreshOutcome.ReuseDetected();
            }

            if (token.getRevokedAt() != null) {
                return new RefreshOutcome.Invalid();
            }

            if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
                refreshTokenRepository.revokeFamily(token.getFamilyId());
                return new RefreshOutcome.IneligibleUser();
            }

            List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());
            if (activeMemberships.isEmpty()) {
                refreshTokenRepository.revokeFamily(token.getFamilyId());
                return new RefreshOutcome.NoActiveMembership();
            }

            Membership selectedMembership = null;
            if (requestedWorkspaceId != null) {
                selectedMembership = activeMemberships.stream()
                    .filter(membership -> membership.getWorkspace().getId().equals(requestedWorkspaceId))
                    .findFirst()
                    .orElse(null);
            }
            if (selectedMembership == null && activeMemberships.size() == 1) {
                selectedMembership = activeMemberships.get(0);
            }

            token.setRotatedAt(now);
            refreshTokenRepository.save(token);

            String rawChildToken = secureTokenGenerator.generate();
            String childTokenHash = tokenHasher.hashRefreshToken(rawChildToken);

            RefreshToken child = new RefreshToken();
            child.setUser(user);
            child.setFamilyId(token.getFamilyId());
            child.setParentToken(token);
            child.setTokenHash(childTokenHash);
            child.setExpiresAt(token.getExpiresAt());
            if (context.ipAddress() != null && !context.ipAddress().isBlank()) {
                child.setIpHash(tokenHasher.hashAuditIp(context.ipAddress()));
            }
            String safeAgent = safeUserAgent(context.userAgent());
            if (!safeAgent.isBlank()) {
                child.setUserAgentHash(tokenHasher.hashUserAgent(safeAgent));
            }
            refreshTokenRepository.save(child);

            AuthSessionResponse responsePayload;
            if (selectedMembership != null) {
                AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                    user.getId(),
                    selectedMembership.getId(),
                    selectedMembership.getWorkspace().getId(),
                    selectedMembership.getRole(),
                    user.getTokenVersion()
                );
                String accessToken = jwtService.issue(principal);
                int expiresInSeconds = (int) jwtService.accessTokenTtlSeconds();
                UserSummary userSummary = UserSummary.from(user);
                MembershipSummary membershipSummary = MembershipSummary.from(selectedMembership);
                List<WorkspaceSummaryResponse> workspaces = activeMemberships.stream()
                    .map(WorkspaceSummaryResponse::from)
                    .toList();
                responsePayload = new AuthSessionResponse(
                    accessToken,
                    expiresInSeconds,
                    false,
                    userSummary,
                    membershipSummary,
                    workspaces
                );
            } else {
                UserSummary userSummary = UserSummary.from(user);
                List<WorkspaceSummaryResponse> workspaces = activeMemberships.stream()
                    .map(WorkspaceSummaryResponse::from)
                    .toList();
                responsePayload = new AuthSessionResponse(
                    null,
                    null,
                    true,
                    userSummary,
                    null,
                    workspaces
                );
            }

            auditService.record(
                AuditAction.SESSION_REFRESHED,
                user,
                selectedMembership,
                selectedMembership != null ? selectedMembership.getWorkspace() : null,
                "USER",
                user.getId(),
                Map.of("familyId", token.getFamilyId().toString()),
                context.ipAddress(),
                context.userAgent()
            );

            return new RefreshOutcome.Success(responsePayload, rawChildToken, child.getExpiresAt());
        });
    }

    public SwitchOutcome switchWorkspace(String rawCookieValue, UUID targetWorkspaceId, AccountRequestContext context) {
        if (!SecureTokenGenerator.isWellFormed(rawCookieValue)) {
            return new SwitchOutcome.Invalid();
        }

        String tokenHash = tokenHasher.hashRefreshToken(rawCookieValue);

        Optional<RefreshTokenProjection> projection = refreshTokenRepository.findIdAndUserIdByTokenHash(tokenHash);
        if (projection.isEmpty()) {
            return new SwitchOutcome.Invalid();
        }

        UUID tokenId = projection.get().getId();
        UUID userId = projection.get().getUserId();

        return transactionTemplate.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) {
                return new SwitchOutcome.Invalid();
            }

            RefreshToken token = refreshTokenRepository.findByIdForUpdate(tokenId).orElse(null);
            if (token == null || !token.getUser().getId().equals(user.getId())) {
                return new SwitchOutcome.Invalid();
            }

            Instant now = clock.instant();

            if (!token.getExpiresAt().isAfter(now)) {
                return new SwitchOutcome.Invalid();
            }

            if (token.getRotatedAt() != null) {
                commitReuseDetection(user, token, now, context);
                return new SwitchOutcome.ReuseDetected();
            }

            if (token.getRevokedAt() != null) {
                return new SwitchOutcome.Invalid();
            }

            if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null) {
                refreshTokenRepository.revokeFamily(token.getFamilyId());
                return new SwitchOutcome.IneligibleUser();
            }

            Optional<Membership> targetMembershipOpt = activeMembershipService.getActiveMembership(user.getId(), targetWorkspaceId);
            if (targetMembershipOpt.isEmpty()) {
                return new SwitchOutcome.WorkspaceNotFound();
            }

            Membership targetMembership = targetMembershipOpt.get();
            List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());

            AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                user.getId(),
                targetMembership.getId(),
                targetMembership.getWorkspace().getId(),
                targetMembership.getRole(),
                user.getTokenVersion()
            );
            String accessToken = jwtService.issue(principal);
            int expiresInSeconds = (int) jwtService.accessTokenTtlSeconds();

            UserSummary userSummary = UserSummary.from(user);
            MembershipSummary membershipSummary = MembershipSummary.from(targetMembership);
            List<WorkspaceSummaryResponse> workspaces = activeMemberships.stream()
                .map(WorkspaceSummaryResponse::from)
                .toList();

            AuthSessionResponse responsePayload = new AuthSessionResponse(
                accessToken,
                expiresInSeconds,
                false,
                userSummary,
                membershipSummary,
                workspaces
            );

            auditService.record(
                AuditAction.WORKSPACE_SWITCHED,
                user,
                targetMembership,
                targetMembership.getWorkspace(),
                "USER",
                user.getId(),
                Map.of("familyId", token.getFamilyId().toString()),
                context.ipAddress(),
                context.userAgent()
            );

            return new SwitchOutcome.Success(responsePayload);
        });
    }

    public void logout(String rawCookieValue, AccountRequestContext context) {
        if (!SecureTokenGenerator.isWellFormed(rawCookieValue)) {
            return;
        }

        String tokenHash = tokenHasher.hashRefreshToken(rawCookieValue);
        Optional<RefreshTokenProjection> projection = refreshTokenRepository.findIdAndUserIdByTokenHash(tokenHash);
        if (projection.isEmpty()) {
            return;
        }

        UUID tokenId = projection.get().getId();
        UUID userId = projection.get().getUserId();

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) {
                return;
            }

            RefreshToken token = refreshTokenRepository.findByIdForUpdate(tokenId).orElse(null);
            if (token == null || !token.getUser().getId().equals(user.getId())) {
                return;
            }

            Instant now = clock.instant();

            if (!token.getExpiresAt().isAfter(now)) {
                return;
            }

            if (token.getRotatedAt() != null) {
                commitReuseDetection(user, token, now, context);
                return;
            }

            if (token.getRevokedAt() == null) {
                refreshTokenRepository.revokeFamily(token.getFamilyId());

                auditService.record(
                    AuditAction.LOGOUT,
                    user,
                    null,
                    null,
                    "USER",
                    user.getId(),
                    Map.of("familyId", token.getFamilyId().toString()),
                    context.ipAddress(),
                    context.userAgent()
                );
            }
        });
    }

    private void commitReuseDetection(
            User user,
            RefreshToken presentedToken,
            Instant now,
            AccountRequestContext context) {
        UUID familyId = presentedToken.getFamilyId();
        boolean alreadyDetected = refreshTokenRepository.existsByFamilyIdAndReuseDetectedAtIsNotNull(familyId);

        if (!alreadyDetected) {
            presentedToken.setReuseDetectedAt(now);
            refreshTokenRepository.save(presentedToken);
        }

        refreshTokenRepository.revokeFamily(familyId);

        if (alreadyDetected) {
            return;
        }

        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        auditService.record(
            AuditAction.REFRESH_REUSE_DETECTED,
            user,
            null,
            null,
            "USER",
            user.getId(),
            Map.of("familyId", familyId.toString()),
            context.ipAddress(),
            context.userAgent()
        );
    }

    private static String safeUserAgent(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.replaceAll("\\p{Cntrl}", "").trim();
        return stripped.length() <= MAX_USER_AGENT_LENGTH
            ? stripped
            : stripped.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
