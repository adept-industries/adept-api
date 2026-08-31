package com.adept.api.alert;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.alert.dto.AlertRuleResponse;
import com.adept.api.alert.dto.CreateAlertRuleRequest;
import com.adept.api.alert.dto.UpdateAlertRuleRequest;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;
    private final CurrentPrincipal currentPrincipal;

    public AlertRuleController(AlertRuleService alertRuleService, CurrentPrincipal currentPrincipal) {
        this.alertRuleService = alertRuleService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ResponseEntity<List<AlertRuleResponse>> list(
            @RequestParam(name = "repositoryId", required = false) UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return ResponseEntity.ok(alertRuleService.list(principal, repositoryId));
    }

    @PostMapping
    public ResponseEntity<AlertRuleResponse> create(
            @Valid @RequestBody CreateAlertRuleRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        AlertRuleResponse response = alertRuleService.create(
            principal,
            request,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.created(URI.create("/api/v1/alert-rules/" + response.id())).body(response);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AlertRuleResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateAlertRuleRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        AlertRuleResponse response = alertRuleService.update(
            principal,
            id,
            request,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        alertRuleService.delete(principal, id, AccountRequestContext.from(servletRequest));
        return ResponseEntity.noContent().build();
    }
}
