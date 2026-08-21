package com.adept.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.risk.dto.DxScoreResponse;
import com.adept.api.risk.dto.PullRequestRiskResponse;
import com.adept.api.risk.dto.PullRequestStaleResponse;
import com.adept.api.risk.dto.RiskModelMetadataResponse;
import com.adept.api.risk.dto.RiskyPullRequestResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.security.RepositoryScopeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PullRequestRiskControllerTest {

    @Mock
    private PullRequestRiskService pullRequestRiskService;

    @Mock
    private RepositoryScopeService repositoryScopeService;

    @Mock
    private CurrentPrincipal currentPrincipal;

    @InjectMocks
    private PullRequestRiskController pullRequestRiskController;

    private UUID workspaceId;
    private UUID repositoryId;
    private AuthenticatedPrincipal principal;
    private GitRepository repository;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        principal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            workspaceId,
            MembershipRole.MANAGER,
            1
        );

        repository = new GitRepository();
        repository.setId(repositoryId);
    }

    @Test
    void getPullRequestRiskReturnsOkWhenAuthorized() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenReturn(repository);

        PullRequestRiskResponse mockResponse = new PullRequestRiskResponse(
            repositoryId,
            42,
            new BigDecimal("0.650000"),
            "HIGH",
            "risk-xgb-prod-v1",
            new BigDecimal("0.300000"),
            List.of(),
            Instant.now(),
            "live"
        );

        when(pullRequestRiskService.getLatestRisk(workspaceId, repositoryId, 42))
            .thenReturn(mockResponse);

        ResponseEntity<PullRequestRiskResponse> response = pullRequestRiskController.getPullRequestRisk(repositoryId, 42);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().prNumber()).isEqualTo(42);
        assertThat(response.getBody().riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void getPullRequestRiskFailsWhenRepositoryScopeRejectsPrincipal() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenThrow(new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        assertThatThrownBy(() -> pullRequestRiskController.getPullRequestRisk(repositoryId, 42))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listRiskyPullRequestsReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenReturn(repository);

        RiskyPullRequestResponse pr = new RiskyPullRequestResponse(
            repositoryId,
            42,
            "Fix security vulnerability",
            "alice",
            new BigDecimal("0.850000"),
            "HIGH",
            List.of(),
            Instant.now(),
            "risk-xgb-prod-v1"
        );

        when(pullRequestRiskService.listRiskyPullRequests(workspaceId, repositoryId, "HIGH"))
            .thenReturn(List.of(pr));

        ResponseEntity<List<RiskyPullRequestResponse>> response =
            pullRequestRiskController.listRiskyPullRequests(repositoryId, "HIGH");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void recalculateRiskReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenReturn(repository);

        PullRequestRiskResponse mockResponse = new PullRequestRiskResponse(
            repositoryId,
            42,
            new BigDecimal("0.450000"),
            "HIGH",
            "risk-xgb-prod-v1",
            new BigDecimal("0.300000"),
            List.of(),
            Instant.now(),
            "live"
        );

        when(pullRequestRiskService.recalculateRisk(workspaceId, repositoryId, 42))
            .thenReturn(mockResponse);

        ResponseEntity<PullRequestRiskResponse> response = pullRequestRiskController.recalculateRisk(repositoryId, 42);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void checkPullRequestStaleReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenReturn(repository);

        PullRequestStaleResponse mockResponse = new PullRequestStaleResponse(
            repositoryId,
            42,
            true,
            true,
            120.0,
            145.0,
            "Open PR inactive for 145.0h"
        );

        when(pullRequestRiskService.checkStale(workspaceId, repositoryId, 42, 120.0))
            .thenReturn(mockResponse);

        ResponseEntity<PullRequestStaleResponse> response =
            pullRequestRiskController.checkPullRequestStale(repositoryId, 42, 120.0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isStale()).isTrue();
    }

    @Test
    void getRepositoryDxScoreReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);
        when(repositoryScopeService.requireReadableRepository(principal, repositoryId))
            .thenReturn(repository);

        DxScoreResponse mockResponse = new DxScoreResponse(
            repositoryId,
            "my-org/backend",
            92.0,
            Map.of("flow", 95.0, "review_wait", 90.0),
            Map.of("flow", 0.20, "review_wait", 0.25)
        );

        when(pullRequestRiskService.getDxScore(workspaceId, repositoryId))
            .thenReturn(mockResponse);

        ResponseEntity<DxScoreResponse> response = pullRequestRiskController.getRepositoryDxScore(repositoryId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().score()).isEqualTo(92.0);
    }
}
