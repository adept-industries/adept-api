package com.adept.api.metric;

import java.time.Instant;
import java.util.List;
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
import com.adept.api.metric.dto.ChangeLeadTimeDetailDto;
import com.adept.api.metric.dto.ChangeLeadTimeDetailsResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Change Lead Time endpoint on {@link MetricController}.
 */
@ExtendWith(MockitoExtension.class)
class MetricControllerChangeLeadTimeTest {

    @Mock
    private MetricService metricService;

    @Mock
    private CurrentPrincipal currentPrincipal;

    @InjectMocks
    private MetricController metricController;

    private AuthenticatedPrincipal principal;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        principal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            workspaceId,
            MembershipRole.MANAGER,
            1
        );
    }

    @Test
    void testGetChangeLeadTimeDetailsReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);

        Instant firstCommit = Instant.parse("2026-09-10T08:00:00Z");
        Instant opened      = Instant.parse("2026-09-10T09:00:00Z");
        Instant merged      = Instant.parse("2026-09-11T10:00:00Z");
        Instant deployed    = Instant.parse("2026-09-11T10:30:00Z");

        UUID prId    = UUID.randomUUID();
        UUID repoId  = UUID.randomUUID();

        ChangeLeadTimeDetailDto row = new ChangeLeadTimeDetailDto(
            prId,
            142,
            "Add search index caching",
            "https://github.com/acme/engine/pull/142",
            "rangaNP",
            repoId,
            "engine",
            "acme",
            "acme/engine",
            firstCommit,
            opened,
            merged,
            deployed,
            // lead = 90600s  (deployed - firstCommit)
            90600L,
            // coding = 3600s (opened - firstCommit)
            3600L,
            // review = 90000s (merged - opened)
            90000L,
            // deploy = 1800s (deployed - merged)
            1800L,
            "production",
            "abc1234"
        );

        ChangeLeadTimeDetailsResponse mockResponse = new ChangeLeadTimeDetailsResponse(
            workspaceId,
            null,
            null,
            1,
            firstCommit,
            deployed,
            "UTC",
            0,
            20,
            1L,
            1,
            List.of(row)
        );

        when(metricService.getChangeLeadTimeDetails(principal, null, null, null, null, 0, 20))
            .thenReturn(mockResponse);

        ResponseEntity<ChangeLeadTimeDetailsResponse> response =
            metricController.getChangeLeadTimeDetails(null, null, null, null, 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().items()).hasSize(1);

        ChangeLeadTimeDetailDto item = response.getBody().items().get(0);
        assertThat(item.prNumber()).isEqualTo(142);
        assertThat(item.prTitle()).isEqualTo("Add search index caching");
        assertThat(item.authorLogin()).isEqualTo("rangaNP");
        assertThat(item.leadTimeSeconds()).isEqualTo(90600L);
        assertThat(item.codingTimeSeconds()).isEqualTo(3600L);
        assertThat(item.reviewTimeSeconds()).isEqualTo(90000L);
        assertThat(item.deployTimeSeconds()).isEqualTo(1800L);
        assertThat(item.deploymentEnvironment()).isEqualTo("production");
    }

    @Test
    void testGetChangeLeadTimeDetailsReturnsEmptyWhenNoData() {
        when(currentPrincipal.require()).thenReturn(principal);

        Instant now = Instant.now();
        ChangeLeadTimeDetailsResponse emptyResponse = new ChangeLeadTimeDetailsResponse(
            workspaceId,
            null,
            null,
            0,
            now.minusSeconds(86400),
            now,
            "UTC",
            0,
            20,
            0L,
            0,
            List.of()
        );

        when(metricService.getChangeLeadTimeDetails(principal, null, null, null, null, 0, 20))
            .thenReturn(emptyResponse);

        ResponseEntity<ChangeLeadTimeDetailsResponse> response =
            metricController.getChangeLeadTimeDetails(null, null, null, null, 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().items()).isEmpty();
        assertThat(response.getBody().totalElements()).isZero();
    }
}
