package com.adept.api.auth.google;

import java.io.IOException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.adept.api.auth.AccountRequestContext;
import com.adept.api.auth.LoginResult;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.RefreshCookieService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthSuccessHandler.class);

    private final GoogleAuthService googleAuthService;
    private final GoogleOAuthSessionService oauthSessionService;
    private final RefreshCookieService refreshCookieService;
    private final CsrfCookieService csrfCookieService;
    private final URI frontendBaseUrl;

    GoogleOAuthSuccessHandler(
            GoogleAuthService googleAuthService,
            GoogleOAuthSessionService oauthSessionService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService,
            AppProperties appProperties) {
        this.googleAuthService = googleAuthService;
        this.oauthSessionService = oauthSessionService;
        this.refreshCookieService = refreshCookieService;
        this.csrfCookieService = csrfCookieService;
        this.frontendBaseUrl = appProperties.frontendBaseUrl();
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        SecurityContextHolder.clearContext();

        try {
            if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                    || !(oauth.getPrincipal() instanceof OidcUser oidcUser)) {
                fail(request, response, "authentication_failed");
                return;
            }

            VerifiedGoogleIdentity identity = VerifiedGoogleIdentity.from(oidcUser);
            GoogleAuthService.AuthenticationOutcome outcome = googleAuthService.authenticate(
                identity,
                AccountRequestContext.from(request)
            );

            if (outcome instanceof GoogleAuthService.AuthenticationOutcome.Authenticated authenticated) {
                finishLogin(request, response, authenticated.login());
                return;
            }
            if (outcome instanceof GoogleAuthService.AuthenticationOutcome.SignupRequired signup) {
                oauthSessionService.keepForSignup(request, signup.pending());
                csrfCookieService.expire(request, response);
                redirect(response, "google/onboarding");
                return;
            }
            fail(request, response, "account_exists");
        } catch (ApiException exception) {
            fail(request, response, errorCode(exception.code()));
        } catch (RuntimeException exception) {
            log.warn(
                "Google authentication failed after provider validation: {}",
                exception.getClass().getSimpleName()
            );
            fail(request, response, "authentication_failed");
        }
    }

    private void finishLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            LoginResult login) throws IOException {
        oauthSessionService.clear(request, response);
        refreshCookieService.set(response, login.rawRefreshToken(), login.refreshExpiresAt());
        csrfCookieService.expire(request, response);
        redirect(response, "login?google=success");
    }

    private void fail(
            HttpServletRequest request,
            HttpServletResponse response,
            String code) throws IOException {
        oauthSessionService.clear(request, response);
        csrfCookieService.expire(request, response);
        redirect(response, "login?google_error=" + code);
    }

    private void redirect(HttpServletResponse response, String relativePath) throws IOException {
        response.sendRedirect(frontendBaseUrl.resolve(relativePath).toString());
    }

    private static String errorCode(ProblemCode code) {
        return switch (code) {
            case GOOGLE_ACCOUNT_CONFLICT -> "account_exists";
            case EMAIL_NOT_VERIFIED -> "email_not_verified";
            case NO_ACTIVE_MEMBERSHIP -> "no_workspace";
            default -> "authentication_failed";
        };
    }
}
