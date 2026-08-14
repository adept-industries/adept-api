package com.adept.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.dto.ActionTokenRequest;
import com.adept.api.auth.dto.AuthSessionResponse;
import com.adept.api.auth.dto.EmailRequest;
import com.adept.api.auth.dto.LoginRequest;
import com.adept.api.auth.dto.MeResponse;
import com.adept.api.auth.dto.PasswordReauthenticationRequest;
import com.adept.api.auth.dto.RefreshRequest;
import com.adept.api.auth.dto.ResetPasswordRequest;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.security.RefreshCookieService;
import com.adept.api.workspace.dto.CreateWorkspaceRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieService refreshCookieService;
    private final CsrfCookieService csrfCookieService;
    private final CurrentPrincipal currentPrincipal;

    public AuthController(
            AuthService authService,
            RefreshTokenService refreshTokenService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService,
            CurrentPrincipal currentPrincipal) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.refreshCookieService = refreshCookieService;
        this.csrfCookieService = csrfCookieService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        MeResponse response = authService.getMe(principal);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthSessionResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        LoginResult result = authService.login(
            request,
            AccountRequestContext.from(servletRequest)
        );
        refreshCookieService.set(servletResponse, result.rawRefreshToken(), result.refreshExpiresAt());
        csrfCookieService.expire(servletRequest, servletResponse);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(result.response());
    }

    @PostMapping("/reauthenticate/password")
    public ResponseEntity<AuthSessionResponse> reauthenticatePassword(
            @Valid @RequestBody PasswordReauthenticationRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        LoginResult result = authService.reauthenticatePassword(
            principal,
            request,
            AccountRequestContext.from(servletRequest)
        );
        refreshCookieService.set(servletResponse, result.rawRefreshToken(), result.refreshExpiresAt());
        csrfCookieService.expire(servletRequest, servletResponse);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthSessionResponse> refresh(
            @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Optional<String> rawCookie = refreshCookieService.read(servletRequest);
        if (rawCookie.isEmpty()) {
            refreshCookieService.clear(servletResponse);
            throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
        }

        RefreshTokenService.RefreshOutcome outcome = refreshTokenService.rotate(
            rawCookie.get(),
            request != null ? request.workspaceId() : null,
            AccountRequestContext.from(servletRequest)
        );

        if (outcome instanceof RefreshTokenService.RefreshOutcome.Success success) {
            refreshCookieService.set(servletResponse, success.rawChildRefreshToken(), success.refreshExpiresAt());
            return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(success.response());
        }

        refreshCookieService.clear(servletResponse);

        if (outcome instanceof RefreshTokenService.RefreshOutcome.ReuseDetected) {
            throw new UnauthorizedException(ProblemCode.REFRESH_REUSE_DETECTED);
        }
        throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
    }

    @PostMapping("/switch-workspace/{workspaceId}")
    public ResponseEntity<AuthSessionResponse> switchWorkspace(
            @PathVariable UUID workspaceId,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Optional<String> rawCookie = refreshCookieService.read(servletRequest);
        if (rawCookie.isEmpty()) {
            refreshCookieService.clear(servletResponse);
            throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
        }

        RefreshTokenService.SwitchOutcome outcome = refreshTokenService.switchWorkspace(
            rawCookie.get(),
            workspaceId,
            AccountRequestContext.from(servletRequest)
        );

        if (outcome instanceof RefreshTokenService.SwitchOutcome.Success success) {
            return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(success.response());
        }

        if (outcome instanceof RefreshTokenService.SwitchOutcome.WorkspaceNotFound) {
            throw new NotFoundException(ProblemCode.WORKSPACE_NOT_FOUND);
        }

        refreshCookieService.clear(servletResponse);

        if (outcome instanceof RefreshTokenService.SwitchOutcome.ReuseDetected) {
            throw new UnauthorizedException(ProblemCode.REFRESH_REUSE_DETECTED);
        }
        throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
    }

    @PostMapping("/workspaces")
    public ResponseEntity<AuthSessionResponse> createWorkspaceForSession(
            @Valid @RequestBody CreateWorkspaceRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Optional<String> rawCookie = refreshCookieService.read(servletRequest);
        if (rawCookie.isEmpty()) {
            refreshCookieService.clear(servletResponse);
            throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
        }

        RefreshTokenService.WorkspaceCreationOutcome outcome =
            refreshTokenService.createWorkspaceForEmptyAccount(
                rawCookie.get(),
                request,
                AccountRequestContext.from(servletRequest)
            );

        if (outcome instanceof RefreshTokenService.WorkspaceCreationOutcome.Success success) {
            return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(success.response());
        }
        if (outcome instanceof RefreshTokenService.WorkspaceCreationOutcome.ActiveMembershipExists) {
            throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
        }

        refreshCookieService.clear(servletResponse);
        if (outcome instanceof RefreshTokenService.WorkspaceCreationOutcome.ReuseDetected) {
            throw new UnauthorizedException(ProblemCode.REFRESH_REUSE_DETECTED);
        }
        throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Optional<String> rawCookie = refreshCookieService.read(servletRequest);
        if (rawCookie.isPresent()) {
            refreshTokenService.logout(
                rawCookie.get(),
                AccountRequestContext.from(servletRequest)
            );
        }
        refreshCookieService.clear(servletResponse);
        csrfCookieService.expire(servletRequest, servletResponse);
        return noStoreNoContent();
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest servletRequest) {
        SignupResponse response = authService.signup(
            request,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.status(201)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(response);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(
            @Valid @RequestBody ActionTokenRequest request,
            HttpServletRequest servletRequest) {
        authService.verifyEmail(request.token(), AccountRequestContext.from(servletRequest));
        return noStoreNoContent();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @Valid @RequestBody EmailRequest request,
            HttpServletRequest servletRequest) {
        authService.resendVerification(request.email(), AccountRequestContext.from(servletRequest));
        return ResponseEntity.accepted()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody EmailRequest request,
            HttpServletRequest servletRequest) {
        authService.forgotPassword(request.email(), AccountRequestContext.from(servletRequest));
        return ResponseEntity.accepted()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        authService.resetPassword(
            request.token(),
            request.newPassword(),
            AccountRequestContext.from(servletRequest)
        );
        refreshCookieService.clear(servletResponse);
        csrfCookieService.expire(servletRequest, servletResponse);
        return noStoreNoContent();
    }

    private static ResponseEntity<Void> noStoreNoContent() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }
}
