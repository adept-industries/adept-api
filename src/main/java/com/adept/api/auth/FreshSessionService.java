package com.adept.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.dto.AuthSessionResponse;
import com.adept.api.auth.dto.MembershipSummary;
import com.adept.api.auth.dto.UserSummary;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

@Service
public final class FreshSessionService {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenHasher tokenHasher;
    private final SecureTokenGenerator secureTokenGenerator;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final Clock clock;

    public FreshSessionService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            TokenHasher tokenHasher,
            SecureTokenGenerator secureTokenGenerator,
            JwtService jwtService,
            AuditService auditService,
            AppProperties appProperties,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.tokenHasher = tokenHasher;
        this.secureTokenGenerator = secureTokenGenerator;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /** Issues a new Adept-owned session. The caller must hold the user transaction lock. */
    public LoginResult issue(
            User user,
            List<Membership> activeMemberships,
            AccountRequestContext context,
            String authenticationMethod) {
        return issue(user, activeMemberships, null, context, authenticationMethod);
    }

    /**
     * Issues a new Adept-owned session and keeps the requested workspace active.
     * The caller must hold the user transaction lock.
     */
    public LoginResult issue(
            User user,
            List<Membership> activeMemberships,
            UUID preferredWorkspaceId,
            AccountRequestContext context,
            String authenticationMethod) {
        if (activeMemberships == null) {
            throw new IllegalArgumentException("active memberships are required");
        }

        Instant now = clock.instant();
        user.setLastLoginAt(now);
        userRepository.save(user);

        String rawRefreshToken = secureTokenGenerator.generate();
        Instant refreshExpiresAt = now.plus(appProperties.refreshToken().ttl());

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setFamilyId(UUID.randomUUID());
        refreshToken.setTokenHash(tokenHasher.hashRefreshToken(rawRefreshToken));
        refreshToken.setExpiresAt(refreshExpiresAt);
        refreshToken.setAuthenticatedAt(now);
        if (context.ipAddress() != null && !context.ipAddress().isBlank()) {
            refreshToken.setIpHash(tokenHasher.hashAuditIp(context.ipAddress()));
        }
        String safeAgent = safeUserAgent(context.userAgent());
        if (!safeAgent.isBlank()) {
            refreshToken.setUserAgentHash(tokenHasher.hashUserAgent(safeAgent));
        }
        refreshTokenRepository.save(refreshToken);

        Membership currentMembership = preferredWorkspaceId == null
            ? activeMemberships.size() == 1 ? activeMemberships.getFirst() : null
            : activeMemberships.stream()
                .filter(membership -> membership.getWorkspace().getId().equals(preferredWorkspaceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("preferred workspace is not active"));
        AuthSessionResponse response = response(user, currentMembership, activeMemberships, now);

        auditService.record(
            AuditAction.LOGIN_SUCCEEDED,
            user,
            currentMembership,
            currentMembership != null ? currentMembership.getWorkspace() : null,
            "USER",
            user.getId(),
            Map.of(
                "authenticationMethod", authenticationMethod,
                "workspaceSelectionRequired", currentMembership == null
            ),
            context.ipAddress(),
            context.userAgent()
        );

        return new LoginResult(response, rawRefreshToken, refreshExpiresAt);
    }

    private AuthSessionResponse response(
            User user,
            Membership currentMembership,
            List<Membership> activeMemberships,
            Instant authenticatedAt) {
        UserSummary userSummary = UserSummary.from(user);
        List<WorkspaceSummaryResponse> workspaces = activeMemberships.stream()
            .map(WorkspaceSummaryResponse::from)
            .toList();
        if (currentMembership == null) {
            return new AuthSessionResponse(null, null, true, userSummary, null, workspaces);
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            user.getId(),
            currentMembership.getId(),
            currentMembership.getWorkspace().getId(),
            currentMembership.getRole(),
            user.getTokenVersion(),
            authenticatedAt
        );
        return new AuthSessionResponse(
            jwtService.issue(principal),
            (int) jwtService.accessTokenTtlSeconds(),
            false,
            userSummary,
            MembershipSummary.from(currentMembership),
            workspaces
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
