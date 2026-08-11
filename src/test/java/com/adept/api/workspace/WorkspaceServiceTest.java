package com.adept.api.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.user.User;
import com.adept.api.workspace.dto.CurrentWorkspaceResponse;
import com.adept.api.workspace.dto.UpdateWorkspaceRequest;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private WorkspaceService workspaceService;

    private UUID userId;
    private UUID workspaceId;
    private UUID membershipId;
    private AuthenticatedPrincipal managerPrincipal;
    private AuthenticatedPrincipal leadPrincipal;
    private Membership membership;
    private Workspace workspace;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        membershipId = UUID.randomUUID();

        user = new User();
        user.setId(userId);

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setName("Original Workspace");
        workspace.setSlug("original-workspace");
        workspace.setTimezone("UTC");

        membership = new Membership();
        membership.setId(membershipId);
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(MembershipRole.MANAGER);

        managerPrincipal = new AuthenticatedPrincipal(userId, membershipId, workspaceId, MembershipRole.MANAGER, 1);
        leadPrincipal = new AuthenticatedPrincipal(userId, membershipId, workspaceId, MembershipRole.LEAD, 1);
    }

    @Test
    void workspaceAuthorizationServiceRequireManagerThrowsForLead() {
        WorkspaceAuthorizationService authService = new WorkspaceAuthorizationService();

        assertThatThrownBy(() -> authService.requireManager(leadPrincipal))
            .isInstanceOf(ForbiddenException.class)
            .extracting(ex -> ((ForbiddenException) ex).code())
            .isEqualTo(ProblemCode.MANAGER_REQUIRED);

        authService.requireManager(managerPrincipal);
    }

    @Test
    void getWorkspacesReturnsSummaryList() {
        when(membershipRepository.findAllActiveWithWorkspaceByUserId(userId))
            .thenReturn(List.of(membership));

        List<WorkspaceSummaryResponse> responses = workspaceService.getWorkspaces(managerPrincipal);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(workspaceId);
        assertThat(responses.get(0).name()).isEqualTo("Original Workspace");
        assertThat(responses.get(0).slug()).isEqualTo("original-workspace");
        assertThat(responses.get(0).role()).isEqualTo(MembershipRole.MANAGER);
    }

    @Test
    void getCurrentWorkspaceRevalidatesAndReturnsSummary() {
        when(membershipRepository.findActiveByUserIdAndWorkspaceId(userId, workspaceId))
            .thenReturn(Optional.of(membership));

        CurrentWorkspaceResponse response = workspaceService.getCurrentWorkspace(managerPrincipal);

        assertThat(response.id()).isEqualTo(workspaceId);
        assertThat(response.name()).isEqualTo("Original Workspace");
        assertThat(response.slug()).isEqualTo("original-workspace");
        assertThat(response.timezone()).isEqualTo("UTC");
        assertThat(response.role()).isEqualTo(MembershipRole.MANAGER);
        assertThat(response.membershipId()).isEqualTo(membershipId);
    }

    @Test
    void getCurrentWorkspaceThrowsWhenNoActiveMembership() {
        when(membershipRepository.findActiveByUserIdAndWorkspaceId(userId, workspaceId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceService.getCurrentWorkspace(managerPrincipal))
            .isInstanceOf(ForbiddenException.class)
            .extracting(ex -> ((ForbiddenException) ex).code())
            .isEqualTo(ProblemCode.NO_ACTIVE_MEMBERSHIP);
    }

    @Test
    void updateCurrentWorkspaceUpdatesFieldsAndAudits() {
        when(membershipRepository.findActiveByUserIdAndWorkspaceId(userId, workspaceId))
            .thenReturn(Optional.of(membership));
        when(workspaceRepository.saveAndFlush(any(Workspace.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWorkspaceRequest request = new UpdateWorkspaceRequest("Renamed Workspace", "Asia/Tokyo");
        AccountRequestContext context = new AccountRequestContext("127.0.0.1", "TestAgent", "trace-123");

        CurrentWorkspaceResponse response = workspaceService.updateCurrentWorkspace(managerPrincipal, request, context);

        assertThat(response.name()).isEqualTo("Renamed Workspace");
        assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(response.slug()).isEqualTo("original-workspace");

        verify(workspaceAuthorizationService).requireManager(managerPrincipal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(java.util.Map.class);

        verify(auditService).record(
            eq(AuditAction.WORKSPACE_UPDATED),
            eq(user),
            eq(membership),
            eq(workspace),
            eq("WORKSPACE"),
            eq(workspaceId),
            metadataCaptor.capture(),
            eq("127.0.0.1"),
            eq("TestAgent")
        );

        java.util.Map<String, Object> metadata = metadataCaptor.getValue();
        assertThat(metadata.get("changedFields")).isEqualTo(List.of("name", "timezone"));
    }
}
