package com.adept.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.dto.ActionTokenRequest;
import com.adept.api.auth.dto.EmailRequest;
import com.adept.api.auth.dto.ResetPasswordRequest;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.RefreshCookieService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieService refreshCookieService;
    private final CsrfCookieService csrfCookieService;

    public AuthController(
            AuthService authService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService) {
        this.authService = authService;
        this.refreshCookieService = refreshCookieService;
        this.csrfCookieService = csrfCookieService;
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
