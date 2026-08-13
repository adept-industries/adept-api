package com.adept.api.auth.google;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.auth.FreshSessionService;
import com.adept.api.auth.LoginResult;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.GoogleAuthProperties;
import com.adept.api.security.ratelimit.AuthRateLimiter;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;
import com.adept.api.workspace.WorkspaceSlugService;

@Service
public final class GoogleAuthService {

    private static final int MAX_SIGNUP_SLUG_ATTEMPTS = 5;

    private final GoogleAuthProperties properties;
    private final GoogleAuthAccountRepository googleAccountRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final WorkspaceSlugService slugService;
    private final ActiveMembershipService activeMembershipService;
    private final FreshSessionService freshSessionService;
    private final AuthRateLimiter rateLimiter;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public GoogleAuthService(
            GoogleAuthProperties properties,
            GoogleAuthAccountRepository googleAccountRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            MembershipRepository membershipRepository,
            WorkspaceSlugService slugService,
            ActiveMembershipService activeMembershipService,
            FreshSessionService freshSessionService,
            AuthRateLimiter rateLimiter,
            AuditService auditService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.googleAccountRepository = googleAccountRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.slugService = slugService;
        this.activeMembershipService = activeMembershipService;
        this.freshSessionService = freshSessionService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AuthenticationOutcome authenticate(
            VerifiedGoogleIdentity identity,
            AccountRequestContext context) {
        requireEnabled();
        Objects.requireNonNull(identity, "identity");
        rateLimiter.requireLogin(identity.email());

        UUID userId = googleAccountRepository.findUserIdByGoogleSubject(identity.subject()).orElse(null);
        if (userId == null) {
            if (userRepository.existsByEmailIgnoreCase(identity.email())) {
                return new AuthenticationOutcome.AccountConflict();
            }
            return new AuthenticationOutcome.SignupRequired(new GoogleSignupSession(
                identity.subject(),
                identity.email(),
                identity.displayName(),
                identity.avatarUrl(),
                clock.instant().plus(properties.onboardingTtl())
            ));
        }

        LoginResult login = transactionTemplate.execute(status -> authenticateExisting(
            userId,
            identity,
            context
        ));
        return new AuthenticationOutcome.Authenticated(Objects.requireNonNull(login));
    }

    public LoginResult completeSignup(
            GoogleSignupSession pending,
            GoogleOnboardingRequest request,
            AccountRequestContext context) {
        requireEnabled();
        if (pending == null || pending.isExpired(clock.instant())) {
            throw new UnauthorizedException(ProblemCode.GOOGLE_SIGNUP_SESSION_INVALID);
        }
        rateLimiter.requireSignup(pending.email());

        for (int attempt = 0; attempt < MAX_SIGNUP_SLUG_ATTEMPTS; attempt++) {
            try {
                LoginResult result = transactionTemplate.execute(status -> createGoogleAccount(
                    pending,
                    request,
                    context
                ));
                return Objects.requireNonNull(result);
            } catch (DataIntegrityViolationException exception) {
                if (isGoogleOrEmailConflict(exception)) {
                    throw new ConflictException(ProblemCode.GOOGLE_ACCOUNT_CONFLICT);
                }
                if (!isSlugConflict(exception) || attempt == MAX_SIGNUP_SLUG_ATTEMPTS - 1) {
                    throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
                }
            }
        }
        throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
    }

    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new NotFoundException(ProblemCode.ENDPOINT_NOT_FOUND);
        }
    }

    private LoginResult authenticateExisting(
            UUID userId,
            VerifiedGoogleIdentity identity,
            AccountRequestContext context) {
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new UnauthorizedException(ProblemCode.GOOGLE_AUTH_FAILED));
        GoogleAuthAccount account = googleAccountRepository
            .findByGoogleSubjectForUpdate(identity.subject())
            .orElseThrow(() -> new UnauthorizedException(ProblemCode.GOOGLE_AUTH_FAILED));
        if (!account.getUser().getId().equals(user.getId()) || user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(ProblemCode.GOOGLE_AUTH_FAILED);
        }
        if (user.getEmailVerifiedAt() == null) {
            throw new ForbiddenException(ProblemCode.EMAIL_NOT_VERIFIED);
        }

        List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());
        if (activeMemberships.isEmpty()) {
            throw new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP);
        }

        account.setGoogleEmail(identity.email());
        account.setLastAuthenticatedAt(clock.instant());
        googleAccountRepository.save(account);
        return freshSessionService.issue(user, activeMemberships, context, "GOOGLE");
    }

    private LoginResult createGoogleAccount(
            GoogleSignupSession pending,
            GoogleOnboardingRequest request,
            AccountRequestContext context) {
        if (googleAccountRepository.existsByGoogleSubject(pending.subject())
                || userRepository.existsByEmailIgnoreCase(pending.email())) {
            throw new ConflictException(ProblemCode.GOOGLE_ACCOUNT_CONFLICT);
        }

        Instant now = clock.instant();
        User user = new User();
        user.setEmail(pending.email());
        user.setPasswordHash(null);
        user.setDisplayName(pending.displayName());
        user.setAvatarUrl(pending.avatarUrl());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(now);
        user = userRepository.saveAndFlush(user);

        GoogleAuthAccount googleAccount = new GoogleAuthAccount();
        googleAccount.setUser(user);
        googleAccount.setGoogleSubject(pending.subject());
        googleAccount.setGoogleEmail(pending.email());
        googleAccount.setLastAuthenticatedAt(now);
        googleAccountRepository.saveAndFlush(googleAccount);

        Workspace workspace = new Workspace();
        workspace.setName(request.workspaceName().trim());
        workspace.setTimezone(request.timezone());
        workspace.setSlug(slugService.generate(request.workspaceName()));
        workspace = workspaceRepository.saveAndFlush(workspace);

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(MembershipRole.MANAGER);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        auditService.record(
            AuditAction.ACCOUNT_SIGNUP,
            user,
            membership,
            workspace,
            "WORKSPACE",
            workspace.getId(),
            Map.of(
                "authenticationMethod", "GOOGLE",
                "emailVerificationRequired", false
            ),
            context.ipAddress(),
            context.userAgent()
        );

        return freshSessionService.issue(user, List.of(membership), context, "GOOGLE");
    }

    private static boolean isGoogleOrEmailConflict(DataIntegrityViolationException exception) {
        String message = message(exception);
        return message.contains("uq_users_email_lower")
            || message.contains("uq_google_auth_accounts_user")
            || message.contains("uq_google_auth_accounts_subject");
    }

    private static boolean isSlugConflict(DataIntegrityViolationException exception) {
        String message = message(exception);
        return message.contains("workspaces_slug_key") || message.contains("uq_workspaces_slug");
    }

    private static String message(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return (cause == null ? "" : String.valueOf(cause.getMessage())).toLowerCase(java.util.Locale.ROOT);
    }

    public sealed interface AuthenticationOutcome {
        record Authenticated(LoginResult login) implements AuthenticationOutcome {
        }

        record SignupRequired(GoogleSignupSession pending) implements AuthenticationOutcome {
        }

        record AccountConflict() implements AuthenticationOutcome {
        }
    }
}

