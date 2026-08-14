package com.adept.api.auth.google;

import java.net.URI;
import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.AccountRequestContext;
import com.adept.api.auth.LoginResult;
import com.adept.api.auth.dto.AuthSessionResponse;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.GoogleAuthProperties;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.security.RefreshCookieService;
import com.adept.api.security.ratelimit.AuthRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleAuthController {

    private static final URI AUTHORIZATION_URI = URI.create(
        GoogleOAuthConfig.AUTHORIZATION_BASE_URI + "/" + GoogleOAuthConfig.REGISTRATION_ID
    );

    private final GoogleAuthService googleAuthService;
    private final GoogleOAuthSessionService oauthSessionService;
    private final RefreshCookieService refreshCookieService;
    private final CsrfCookieService csrfCookieService;
    private final AuthRateLimiter rateLimiter;
    private final CurrentPrincipal currentPrincipal;
    private final Clock clock;
    private final GoogleAuthProperties googleAuthProperties;

    public GoogleAuthController(
            GoogleAuthService googleAuthService,
            GoogleOAuthSessionService oauthSessionService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService,
            AuthRateLimiter rateLimiter,
            CurrentPrincipal currentPrincipal,
            Clock clock,
            GoogleAuthProperties googleAuthProperties) {
        this.googleAuthService = googleAuthService;
        this.oauthSessionService = oauthSessionService;
        this.refreshCookieService = refreshCookieService;
        this.csrfCookieService = csrfCookieService;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
        this.clock = clock;
        this.googleAuthProperties = googleAuthProperties;
    }

    @PostMapping("/reauthentication/start")
    public ResponseEntity<GoogleReauthenticationStartResponse> startReauthentication(
            HttpServletRequest request) {
        googleAuthService.requireEnabled();
        AuthenticatedPrincipal principal = currentPrincipal.require();
        rateLimiter.requirePeer(request.getRemoteAddr());
        oauthSessionService.beginReauthentication(request, new GoogleReauthenticationSession(
            principal.userId(),
            principal.workspaceId(),
            principal.tokenVersion(),
            clock.instant().plus(googleAuthProperties.onboardingTtl())
        ));
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(new GoogleReauthenticationStartResponse(AUTHORIZATION_URI.toString()));
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(
            HttpServletRequest request,
            HttpServletResponse response) {
        googleAuthService.requireEnabled();
        rateLimiter.requirePeer(request.getRemoteAddr());
        oauthSessionService.begin(request);
        return ResponseEntity.status(302)
            .location(AUTHORIZATION_URI)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }

    @PostMapping("/onboarding")
    public ResponseEntity<AuthSessionResponse> completeOnboarding(
            @Valid @RequestBody GoogleOnboardingRequest onboarding,
            HttpServletRequest request,
            HttpServletResponse response) {
        googleAuthService.requireEnabled();
        GoogleSignupSession pending = oauthSessionService.pendingSignup(request)
            .orElseThrow(() -> new UnauthorizedException(ProblemCode.GOOGLE_SIGNUP_SESSION_INVALID));
        LoginResult login = googleAuthService.completeSignup(
            pending,
            onboarding,
            AccountRequestContext.from(request)
        );

        oauthSessionService.clear(request, response);
        refreshCookieService.set(response, login.rawRefreshToken(), login.refreshExpiresAt());
        csrfCookieService.expire(request, response);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(login.response());
    }
}
