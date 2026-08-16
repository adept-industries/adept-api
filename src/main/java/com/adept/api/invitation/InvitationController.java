package com.adept.api.invitation;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.AccountRequestContext;
import com.adept.api.auth.LoginResult;
import com.adept.api.auth.dto.AuthSessionResponse;
import com.adept.api.invitation.dto.AcceptInvitationRequest;
import com.adept.api.invitation.dto.InvitationPreviewResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.RefreshCookieService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Validated
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitationService;
    private final RefreshCookieService refreshCookieService;
    private final CsrfCookieService csrfCookieService;

    public InvitationController(
            InvitationService invitationService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService) {
        this.invitationService = invitationService;
        this.refreshCookieService = refreshCookieService;
        this.csrfCookieService = csrfCookieService;
    }

    @GetMapping("/preview")
    public ResponseEntity<InvitationPreviewResponse> preview(
            @RequestParam("token") @NotBlank String token) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(invitationService.previewInvitation(token));
    }

    @PostMapping("/accept")
    public ResponseEntity<AuthSessionResponse> accept(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        LoginResult result = invitationService.acceptInvitation(
            request,
            principal,
            AccountRequestContext.from(servletRequest)
        );
        refreshCookieService.set(servletResponse, result.rawRefreshToken(), result.refreshExpiresAt());
        csrfCookieService.expire(servletRequest, servletResponse);
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(result.response());
    }

    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<Void> resend(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID invitationId,
            HttpServletRequest servletRequest) {
        invitationService.resendInvitation(
            principal,
            invitationId,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }

    @DeleteMapping("/{invitationId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID invitationId,
            HttpServletRequest servletRequest) {
        invitationService.revokeInvitation(
            principal,
            invitationId,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .build();
    }
}
