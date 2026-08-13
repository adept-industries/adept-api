package com.adept.api.auth.google;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.LoginResult;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.AppProperties;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "app.google-auth.enabled=true",
    "app.google-auth.client-id=test-google-client.apps.googleusercontent.com",
    "app.google-auth.client-secret=test-google-secret",
    "app.google-auth.redirect-uri=http://localhost:8080/api/v1/auth/google/callback/google",
    "app.google-auth.onboarding-ttl=PT10M"
})
class GoogleAuthIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private GoogleAuthService googleAuthService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Autowired
    private GoogleOAuthSessionService oauthSessionService;

    @Autowired
    private GoogleOAuthSuccessHandler successHandler;

    @Autowired
    private AppProperties appProperties;

    @Test
    void newGoogleIdentityCompletesOnboardingAndCanReturn() {
        VerifiedGoogleIdentity identity = identity("google-subject-new", uniqueEmail("google-new"));

        GoogleAuthService.AuthenticationOutcome initial = googleAuthService.authenticate(
            identity,
            requestContext()
        );
        assertThat(initial).isInstanceOf(GoogleAuthService.AuthenticationOutcome.SignupRequired.class);
        GoogleSignupSession pending = ((GoogleAuthService.AuthenticationOutcome.SignupRequired) initial).pending();

        LoginResult created = googleAuthService.completeSignup(
            pending,
            new GoogleOnboardingRequest("Google Workspace", "Asia/Colombo"),
            requestContext()
        );

        assertThat(created.response().workspaceSelectionRequired()).isFalse();
        assertThat(created.response().user().email()).isEqualTo(identity.email());
        assertThat(created.response().user().emailVerified()).isTrue();
        assertThat(created.response().currentMembership().role().name()).isEqualTo("MANAGER");
        assertThat(created.rawRefreshToken()).hasSize(43);
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?",
            String.class,
            identity.email()
        )).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT google_subject FROM google_auth_accounts WHERE google_email = ?",
            String.class,
            identity.email()
        )).isEqualTo(identity.subject());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_SIGNUP'",
            Integer.class
        )).isEqualTo(1);

        GoogleAuthService.AuthenticationOutcome returning = googleAuthService.authenticate(
            identity,
            requestContext()
        );
        assertThat(returning).isInstanceOf(GoogleAuthService.AuthenticationOutcome.Authenticated.class);
        LoginResult returned = ((GoogleAuthService.AuthenticationOutcome.Authenticated) returning).login();
        assertThat(returned.response().user().id()).isEqualTo(created.response().user().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
    }

    @Test
    void matchingEmailWithoutGoogleMappingIsNotSilentlyLinked() {
        String email = uniqueEmail("google-collision");
        jdbc.update("""
            INSERT INTO users (email, password_hash, display_name, email_verified_at)
            VALUES (?, 'existing-hash', 'Existing User', now())
            """, email);

        GoogleAuthService.AuthenticationOutcome outcome = googleAuthService.authenticate(
            identity("different-google-subject", email),
            requestContext()
        );

        assertThat(outcome).isInstanceOf(GoogleAuthService.AuthenticationOutcome.AccountConflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM google_auth_accounts", Integer.class)).isZero();
    }

    @Test
    void expiredOrReplayedOnboardingCannotCreateAnotherAccount() {
        String email = uniqueEmail("google-expired");
        GoogleSignupSession expired = new GoogleSignupSession(
            "expired-subject",
            email,
            "Expired User",
            null,
            clock.instant().minusSeconds(1)
        );

        assertThatThrownBy(() -> googleAuthService.completeSignup(
            expired,
            new GoogleOnboardingRequest("Expired Workspace", "UTC"),
            requestContext()
        ))
            .isInstanceOfSatisfying(UnauthorizedException.class,
                exception -> assertThat(exception.code()).isEqualTo(ProblemCode.GOOGLE_SIGNUP_SESSION_INVALID));

        VerifiedGoogleIdentity identity = identity("replay-subject", uniqueEmail("google-replay"));
        GoogleSignupSession pending = ((GoogleAuthService.AuthenticationOutcome.SignupRequired)
            googleAuthService.authenticate(identity, requestContext())).pending();
        googleAuthService.completeSignup(
            pending,
            new GoogleOnboardingRequest("First Workspace", "UTC"),
            requestContext()
        );

        assertThatThrownBy(() -> googleAuthService.completeSignup(
            pending,
            new GoogleOnboardingRequest("Second Workspace", "UTC"),
            requestContext()
        ))
            .isInstanceOfSatisfying(ConflictException.class,
                exception -> assertThat(exception.code()).isEqualTo(ProblemCode.GOOGLE_ACCOUNT_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces", Integer.class)).isEqualTo(1);
    }

    @Test
    void startEndpointCreatesStateNonceAndPkceAndInternalEndpointIsGuarded() throws Exception {
        mockMvc.perform(get(GoogleOAuthConfig.AUTHORIZATION_BASE_URI + "/google"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ENDPOINT_NOT_FOUND")));

        MvcResult start = mockMvc.perform(get("/api/v1/auth/google/start")
                .with(request -> {
                    request.setRemoteAddr("192.0.2." + (Math.abs(UUID.randomUUID().hashCode()) % 200 + 1));
                    return request;
                }))
            .andExpect(status().isFound())
            .andExpect(header().string(HttpHeaders.LOCATION,
                GoogleOAuthConfig.AUTHORIZATION_BASE_URI + "/google"))
            .andReturn();

        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult authorization = mockMvc.perform(
                get(GoogleOAuthConfig.AUTHORIZATION_BASE_URI + "/google").session(session))
            .andExpect(status().isFound())
            .andReturn();
        String location = authorization.getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location)
            .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
            .contains("state=")
            .contains("nonce=")
            .contains("code_challenge=")
            .contains("code_challenge_method=S256")
            .contains("redirect_uri=http://localhost:8080/api/v1/auth/google/callback/google");
    }

    @Test
    void onboardingEndpointRequiresCsrfAndReturnsTheNormalAdeptSession() throws Exception {
        VerifiedGoogleIdentity identity = identity("controller-subject", uniqueEmail("google-controller"));
        GoogleSignupSession pending = ((GoogleAuthService.AuthenticationOutcome.SignupRequired)
            googleAuthService.authenticate(identity, requestContext())).pending();

        MockHttpServletRequest seedRequest = new MockHttpServletRequest();
        MockHttpSession oauthSession = new MockHttpSession(seedRequest.getServletContext());
        seedRequest.setSession(oauthSession);
        oauthSessionService.keepForSignup(seedRequest, pending);

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/google/onboarding")
                .session(oauthSession)
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"workspaceName":"Controller Workspace","timezone":"UTC"}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.user.email").value(identity.email()))
            .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .anyMatch(value -> value.startsWith("adept_refresh="))
            .anyMatch(value -> value.startsWith("adept_oauth=;"));
    }

    @Test
    void validatedGoogleIdentityReachesTheNewUserOnboardingRedirect() throws Exception {
        String email = uniqueEmail("google-callback");
        Instant now = clock.instant();
        OidcIdToken idToken = new OidcIdToken(
            "test-id-token",
            now.minusSeconds(10),
            now.plusSeconds(300),
            Map.of(
                "sub", "callback-subject",
                "email", email,
                "email_verified", true,
                "name", "Callback User",
                "picture", "https://example.com/callback.png"
            )
        );
        DefaultOidcUser oidcUser = new DefaultOidcUser(
            List.of(new OidcUserAuthority(idToken)),
            idToken
        );
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            oidcUser,
            oidcUser.getAuthorities(),
            GoogleOAuthConfig.REGISTRATION_ID
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(request.getServletContext()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl())
            .isEqualTo(appProperties.frontendBaseUrl().resolve("google/onboarding").toString());
        assertThat(oauthSessionService.pendingSignup(request))
            .get()
            .extracting(GoogleSignupSession::email)
            .isEqualTo(email);
    }

    private static VerifiedGoogleIdentity identity(String subject, String email) {
        return new VerifiedGoogleIdentity(
            subject,
            email,
            "Google User",
            "https://example.com/avatar.png"
        );
    }
}
