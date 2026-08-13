package com.adept.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.dto.LoginRequest;
import com.adept.api.auth.dto.MeResponse;
import com.adept.api.auth.dto.MembershipSummary;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.auth.dto.UserSummary;
import com.adept.api.common.domain.ActionTokenPurpose;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.crypto.PasswordService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.mail.PasswordChangedMailRequested;
import com.adept.api.mail.PasswordResetMailRequested;
import com.adept.api.mail.VerificationMailRequested;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.ratelimit.AuthRateLimiter;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;
import com.adept.api.workspace.WorkspaceSlugService;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Service
public class AuthService {

    private static final int MAX_SIGNUP_SLUG_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final UserActionTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WorkspaceSlugService slugService;
    private final ActionTokenService actionTokenService;
    private final PasswordService passwordService;
    private final AuthRateLimiter rateLimiter;
    private final AuditService auditService;
    private final ActiveMembershipService activeMembershipService;
    private final TokenHasher tokenHasher;
    private final FreshSessionService freshSessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Validator validator;
    private final TransactionTemplate transactionTemplate;

    public AuthService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            MembershipRepository membershipRepository,
            UserActionTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            WorkspaceSlugService slugService,
            ActionTokenService actionTokenService,
            PasswordService passwordService,
            AuthRateLimiter rateLimiter,
            AuditService auditService,
            ActiveMembershipService activeMembershipService,
            TokenHasher tokenHasher,
            FreshSessionService freshSessionService,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            Validator validator,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.slugService = slugService;
        this.actionTokenService = actionTokenService;
        this.passwordService = passwordService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.activeMembershipService = activeMembershipService;
        this.tokenHasher = tokenHasher;
        this.freshSessionService = freshSessionService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.validator = validator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SignupResponse signup(SignupRequest request, AccountRequestContext context) {
        String normalizedEmail = normalizeEmail(request.email());
        rateLimiter.requireSignup(normalizedEmail);
        for (int attempt = 0; attempt < MAX_SIGNUP_SLUG_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> signupAttempt(request, normalizedEmail, context));
            } catch (DataIntegrityViolationException exception) {
                if (isEmailConflict(exception)) {
                    throw new ConflictException(ProblemCode.EMAIL_ALREADY_EXISTS);
                }
                if (!isSlugConflict(exception) || attempt == MAX_SIGNUP_SLUG_ATTEMPTS - 1) {
                    throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
                }
            }
        }
        throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
    }

    public void verifyEmail(String rawToken, AccountRequestContext context) {
        requireWellFormedToken(rawToken);
        String hash = actionTokenService.hash(ActionTokenPurpose.VERIFY_EMAIL, rawToken);
        rateLimiter.requireActionTokenHash(hash);
        ActionTokenIdentity identity = tokenRepository.findIdentityByTokenHash(hash)
            .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByIdForUpdate(identity.userId())
                .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
            UserActionToken token = tokenRepository.findByIdForUpdate(identity.tokenId())
                .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
            if (!Objects.equals(token.getUser().getId(), user.getId())
                || token.getPurpose() != ActionTokenPurpose.VERIFY_EMAIL) {
                throw new ApiException(ProblemCode.ACTION_TOKEN_INVALID);
            }
            if (token.getConsumedAt() != null) {
                if (user.getEmailVerifiedAt() != null) {
                    return;
                }
                throw new ApiException(ProblemCode.ACTION_TOKEN_INVALID);
            }
            Instant now = clock.instant();
            if (!token.getExpiresAt().isAfter(now)) {
                throw new ApiException(ProblemCode.ACTION_TOKEN_INVALID);
            }
            user.setEmailVerifiedAt(now);
            token.setConsumedAt(now);
            auditService.record(
                AuditAction.EMAIL_VERIFIED,
                user,
                null,
                null,
                "USER",
                user.getId(),
                Map.of("purpose", ActionTokenPurpose.VERIFY_EMAIL.name()),
                context.ipAddress(),
                context.userAgent()
            );
        });
    }

    public void resendVerification(String email, AccountRequestContext context) {
        String normalizedEmail = normalizeEmail(email);
        rateLimiter.requireAccountEmail(normalizedEmail);
        transactionTemplate.executeWithoutResult(status -> userRepository
            .findByEmailIgnoreCaseForUpdate(normalizedEmail)
            .ifPresent(user -> {
                if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() != null) {
                    return;
                }
                issueReplacement(user, ActionTokenPurpose.VERIFY_EMAIL, context);
            }));
    }

    public void forgotPassword(String email, AccountRequestContext context) {
        String normalizedEmail = normalizeEmail(email);
        rateLimiter.requireAccountEmail(normalizedEmail);
        transactionTemplate.executeWithoutResult(status -> userRepository
            .findByEmailIgnoreCaseForUpdate(normalizedEmail)
            .ifPresent(user -> {
                if (user.getStatus() != UserStatus.ACTIVE) {
                    return;
                }
                issueReplacement(user, ActionTokenPurpose.RESET_PASSWORD, context);
            }));
    }

    public void resetPassword(String rawToken, String newPassword, AccountRequestContext context) {
        requireWellFormedToken(rawToken);
        String hash = actionTokenService.hash(ActionTokenPurpose.RESET_PASSWORD, rawToken);
        rateLimiter.requireActionTokenHash(hash);
        ActionTokenIdentity identity = tokenRepository.findIdentityByTokenHash(hash)
            .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findByIdForUpdate(identity.userId())
                .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
            java.util.List<UserActionToken> activeResetTokens =
                tokenRepository.findActiveByUserAndPurposeForUpdate(
                    user.getId(),
                    ActionTokenPurpose.RESET_PASSWORD
                );
            UserActionToken token = activeResetTokens.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), identity.tokenId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ProblemCode.ACTION_TOKEN_INVALID));
            Instant now = clock.instant();
            if (!Objects.equals(token.getUser().getId(), user.getId())
                || token.getPurpose() != ActionTokenPurpose.RESET_PASSWORD
                || !token.getExpiresAt().isAfter(now)) {
                throw new ApiException(ProblemCode.ACTION_TOKEN_INVALID);
            }
            user.setPasswordHash(passwordService.encodeNewPassword(newPassword));
            activeResetTokens.forEach(activeToken -> activeToken.setConsumedAt(now));
            user.setTokenVersion(user.getTokenVersion() + 1);
            refreshTokenRepository.revokeAllForUser(user.getId());
            auditService.record(
                AuditAction.PASSWORD_RESET_COMPLETED,
                user,
                null,
                null,
                "USER",
                user.getId(),
                Map.of("reason", "token_reset"),
                context.ipAddress(),
                context.userAgent()
            );
            eventPublisher.publishEvent(new PasswordChangedMailRequested(
                user.getId(),
                user.getEmail(),
                context.traceId()
            ));
        });
    }

    private SignupResponse signupAttempt(SignupRequest request, String normalizedEmail, AccountRequestContext context) {
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException(ProblemCode.EMAIL_ALREADY_EXISTS);
        }
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordService.encodeNewPassword(request.password()));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

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

        ActionTokenService.IssuedActionToken issued = actionTokenService.issue(
            user,
            ActionTokenPurpose.VERIFY_EMAIL
        );
        auditService.record(
            AuditAction.ACCOUNT_SIGNUP,
            user,
            membership,
            workspace,
            "WORKSPACE",
            workspace.getId(),
            Map.of("emailVerificationRequired", true),
            context.ipAddress(),
            context.userAgent()
        );
        eventPublisher.publishEvent(new VerificationMailRequested(
            user.getId(),
            user.getEmail(),
            issued.rawToken(),
            context.traceId()
        ));
        return new SignupResponse(
            UserSummary.from(user),
            WorkspaceSummaryResponse.from(membership),
            true
        );
    }

    private void issueReplacement(User user, ActionTokenPurpose purpose, AccountRequestContext context) {
        Instant now = clock.instant();
        actionTokenService.consumeActiveTokens(user, purpose, now);
        ActionTokenService.IssuedActionToken issued = actionTokenService.issue(user, purpose);
        if (purpose == ActionTokenPurpose.VERIFY_EMAIL) {
            auditService.record(
                AuditAction.VERIFICATION_EMAIL_REQUESTED,
                user,
                null,
                null,
                "USER",
                user.getId(),
                Map.of("purpose", purpose.name()),
                context.ipAddress(),
                context.userAgent()
            );
            eventPublisher.publishEvent(new VerificationMailRequested(
                user.getId(),
                user.getEmail(),
                issued.rawToken(),
                context.traceId()
            ));
        } else {
            auditService.record(
                AuditAction.PASSWORD_RESET_REQUESTED,
                user,
                null,
                null,
                "USER",
                user.getId(),
                Map.of("purpose", purpose.name()),
                context.ipAddress(),
                context.userAgent()
            );
            eventPublisher.publishEvent(new PasswordResetMailRequested(
                user.getId(),
                user.getEmail(),
                issued.rawToken(),
                context.traceId()
            ));
        }
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireWellFormedToken(String value) {
        if (!SecureTokenGenerator.isWellFormed(value)) {
            throw new ApiException(ProblemCode.ACTION_TOKEN_INVALID);
        }
    }

    private static boolean isEmailConflict(DataIntegrityViolationException exception) {
        return message(exception).contains("uq_users_email_lower");
    }

    private static boolean isSlugConflict(DataIntegrityViolationException exception) {
        String message = message(exception);
        return message.contains("workspaces_slug_key") || message.contains("uq_workspaces_slug");
    }

    private static String message(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        return (cause == null ? "" : String.valueOf(cause.getMessage())).toLowerCase(Locale.ROOT);
    }

    public MeResponse getMe(AuthenticatedPrincipal principal) {
        User user = userRepository.findById(principal.userId())
            .orElseThrow(() -> new UnauthorizedException(ProblemCode.SESSION_INVALID));

        List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());
        Membership currentMembership = activeMembershipService.getActiveMembership(user.getId(), principal.workspaceId()).orElse(null);

        UserSummary userSummary = UserSummary.from(user);
        MembershipSummary membershipSummary = currentMembership != null ? MembershipSummary.from(currentMembership) : null;
        List<WorkspaceSummaryResponse> workspaces = activeMemberships.stream()
            .map(WorkspaceSummaryResponse::from)
            .toList();

        return new MeResponse(userSummary, membershipSummary, workspaces);
    }

    public LoginResult login(LoginRequest request, AccountRequestContext context) {
        rateLimiter.requireLogin(request.email());

        String rawEmail = request.email() == null ? "" : request.email().trim();
        String normalizedEmail = rawEmail.toLowerCase(Locale.ROOT);
        String emailHmac = tokenHasher.hashAuditEmail(normalizedEmail);
        boolean lookupEligible = validator.validate(new LoginEmailLookupCandidate(normalizedEmail)).isEmpty();

        LoginOutcome outcome = transactionTemplate.execute(status -> {
            UUID userId = lookupEligible
                ? userRepository.findIdByEmailIgnoreCase(normalizedEmail).orElse(null)
                : null;
            User user = userId == null ? null : userRepository.findByIdForUpdate(userId).orElse(null);

            String currentHash = user != null ? user.getPasswordHash() : null;
            boolean passwordMatches = passwordService.matchesAuthenticationCandidate(request.password(), currentHash);

            if (user == null || !passwordMatches || user.getStatus() != UserStatus.ACTIVE) {
                recordFailedLogin(emailHmac, context);
                return new LoginOutcome.Failure(ProblemCode.INVALID_CREDENTIALS);
            }

            if (user.getEmailVerifiedAt() == null) {
                recordFailedLogin(emailHmac, context);
                return new LoginOutcome.Failure(ProblemCode.EMAIL_NOT_VERIFIED);
            }

            List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());
            if (activeMemberships.isEmpty()) {
                recordFailedLogin(emailHmac, context);
                return new LoginOutcome.Failure(ProblemCode.NO_ACTIVE_MEMBERSHIP);
            }

            return new LoginOutcome.Success(
                freshSessionService.issue(user, activeMemberships, context, "PASSWORD")
            );
        });

        if (outcome instanceof LoginOutcome.Failure failure) {
            switch (failure.problemCode()) {
                case INVALID_CREDENTIALS -> throw new UnauthorizedException(ProblemCode.INVALID_CREDENTIALS);
                case EMAIL_NOT_VERIFIED, NO_ACTIVE_MEMBERSHIP -> throw new ForbiddenException(failure.problemCode());
                default -> throw new UnauthorizedException(failure.problemCode());
            }
        }

        if (outcome instanceof LoginOutcome.Success success) {
            return success.login();
        }

        throw new IllegalStateException("Unexpected login outcome");
    }

    private void recordFailedLogin(String emailHmac, AccountRequestContext context) {
        auditService.record(
            AuditAction.LOGIN_FAILED,
            null,
            null,
            null,
            "USER",
            null,
            Map.of("reason", "invalid_credentials", "emailHmac", emailHmac),
            context.ipAddress(),
            context.userAgent()
        );
    }

    private sealed interface LoginOutcome {
        record Success(LoginResult login) implements LoginOutcome {}
        record Failure(ProblemCode problemCode) implements LoginOutcome {}
    }

    private record LoginEmailLookupCandidate(
        @NotBlank @Email @Size(max = 320) String value
    ) {}
}
