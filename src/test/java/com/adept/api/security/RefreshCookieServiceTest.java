package com.adept.api.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.support.TestAppProperties;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshCookieServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void setsExactSecureCookieFlagsAndAbsoluteRemainingLifetime() {
        RefreshCookieService service = new RefreshCookieService(TestAppProperties.create(), CLOCK);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = new SecureTokenGenerator().generate();

        service.set(response, token, NOW.plusSeconds(3_600));

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header)
            .startsWith("adept_refresh=" + token)
            .contains("Path=/api/v1/auth")
            .contains("Max-Age=3600")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .doesNotContain("Domain=");
    }

    @Test
    void clearsWithTheSameFlagsAndPath() {
        RefreshCookieService service = new RefreshCookieService(TestAppProperties.create(), CLOCK);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clear(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
            .startsWith("adept_refresh=")
            .contains("Path=/api/v1/auth")
            .contains("Max-Age=0")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .doesNotContain("Domain=");
    }

    @Test
    void explicitLocalConfigurationOmitsSecureFlag() {
        RefreshCookieService service = new RefreshCookieService(
            TestAppProperties.create(TestAppProperties.refreshToken(false)), CLOCK);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.clear(response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure");
    }

    @Test
    void readsOneWellFormedCookieAndRejectsMalformedOrDuplicateValues() {
        RefreshCookieService service = new RefreshCookieService(TestAppProperties.create(), CLOCK);
        String token = new SecureTokenGenerator().generate();
        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.setCookies(new Cookie("adept_refresh", token));

        assertThat(service.read(valid)).contains(token);

        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setCookies(new Cookie("adept_refresh", "submitted-secret"));
        assertThatThrownBy(() -> service.read(malformed))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageNotContaining("submitted-secret");

        MockHttpServletRequest duplicate = new MockHttpServletRequest();
        duplicate.setCookies(
            new Cookie("adept_refresh", token),
            new Cookie("adept_refresh", new SecureTokenGenerator().generate()));
        assertThatThrownBy(() -> service.read(duplicate)).isInstanceOf(UnauthorizedException.class);
    }
}
