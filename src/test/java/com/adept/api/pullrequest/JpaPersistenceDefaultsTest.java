package com.adept.api.pullrequest;

import com.adept.api.AdeptApiApplication;
import com.adept.api.audit.AuditLog;
import com.adept.api.auth.RefreshToken;
import com.adept.api.auth.UserActionToken;
import com.adept.api.common.domain.ActionTokenPurpose;
import com.adept.api.common.domain.DeploymentLinkMethod;
import com.adept.api.common.domain.DeploymentSource;
import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.IssueLinkSource;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.PullRequestState;
import com.adept.api.common.domain.RepositorySelection;
import com.adept.api.common.domain.RepositoryVisibility;
import com.adept.api.common.domain.RiskLevel;
import com.adept.api.common.domain.WebhookSource;
import com.adept.api.deployment.Deployment;
import com.adept.api.deployment.DeploymentPullRequest;
import com.adept.api.integration.common.IntegrationOauthState;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GithubIntegration;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.integration.jira.JiraIntegration;
import com.adept.api.integration.jira.JiraIssue;
import com.adept.api.integration.jira.JiraProject;
import com.adept.api.integration.jira.RepositoryJiraProject;
import com.adept.api.job.ProcessingJob;
import com.adept.api.metric.MetricSnapshot;
import com.adept.api.risk.RiskPrediction;
import com.adept.api.user.User;
import com.adept.api.webhook.RawWebhookEvent;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = AdeptApiApplication.class, properties = {
    "app.frontend-base-url=http://localhost:3000",
    "app.public-api-base-url=http://localhost:8080",
    "app.email-from=Adept Test <test@adept.local>",
    "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.token-hash-pepper-base64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
    "app.integration-encryption.active-key-version=1",
    "app.integration-encryption.keys[1]=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
    "app.github.app-id=1",
    "app.github.app-slug=adept-test",
    "app.github.private-key-base64=dGVzdC1vbmx5",
    "app.github.webhook-secret=test-only",
    "app.jira.client-id=test-only",
    "app.jira.client-secret=test-only",
    "app.jira.callback-url=http://localhost/callback",
    "app.engine.base-url=http://localhost:8000",
    "app.engine.internal-token=test-only",
    "spring.mail.host=localhost",
    "spring.mail.port=1025"
})
class JpaPersistenceDefaultsTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_jpa_defaults_test")
            .withUsername("adept")
            .withPassword("adept");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    EntityManager entityManager;

    @Test
    @Transactional
    void hibernatePersistsJavaDefaultsAndCreationAuditing() {
        Instant now = Instant.now();

        User managerUser = user("manager@adept.test", "Manager");
        User leadUser = user("lead@adept.test", "Lead");
        Workspace workspace = workspace();
        entityManager.persist(managerUser);
        entityManager.persist(leadUser);
        entityManager.persist(workspace);

        Membership manager = membership(workspace, managerUser, MembershipRole.MANAGER);
        Membership lead = membership(workspace, leadUser, MembershipRole.LEAD);
        entityManager.persist(manager);
        entityManager.persist(lead);

        GithubIntegration github = githubIntegration(workspace, manager);
        entityManager.persist(github);

        GitRepository repository = gitRepository(workspace, github);
        entityManager.persist(repository);

        RepositoryLeadAssignment assignment = new RepositoryLeadAssignment();
        assignment.setWorkspace(workspace);
        assignment.setRepository(repository);
        assignment.setLeadMembership(lead);
        assignment.setAssignedBy(manager);
        entityManager.persist(assignment);

        IntegrationOauthState oauthState = new IntegrationOauthState();
        oauthState.setProvider(ExternalProvider.GITHUB);
        oauthState.setWorkspace(workspace);
        oauthState.setInitiatedBy(manager);
        oauthState.setStateHash("state-hash");
        oauthState.setExpiresAt(now.plusSeconds(300));
        entityManager.persist(oauthState);

        RawWebhookEvent rawEvent = new RawWebhookEvent();
        rawEvent.setWorkspace(workspace);
        rawEvent.setRepository(repository);
        rawEvent.setSource(WebhookSource.GITHUB);
        rawEvent.setDeliveryId("delivery-defaults-test");
        rawEvent.setEventType("pull_request");
        rawEvent.setPayload(Map.of("action", "opened"));
        entityManager.persist(rawEvent);

        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(workspace);
        job.setRepository(repository);
        job.setRawEvent(rawEvent);
        job.setJobType(ProcessingJobType.PROCESS_GITHUB_EVENT);
        entityManager.persist(job);

        PullRequest pullRequest = pullRequest(workspace, repository, now);
        entityManager.persist(pullRequest);

        PullRequestFeature feature = new PullRequestFeature();
        feature.setWorkspace(workspace);
        feature.setRepository(repository);
        feature.setPullRequest(pullRequest);
        feature.setFeatureSchemaVersion("test-v1");
        entityManager.persist(feature);

        MetricSnapshot metric = new MetricSnapshot();
        metric.setWorkspace(workspace);
        metric.setRepository(repository);
        metric.setMetricType(MetricType.CHANGE_LEAD_TIME_HOURS);
        metric.setGranularity(MetricGranularity.DAY);
        metric.setPeriodStart(now.minusSeconds(86_400));
        metric.setPeriodEnd(now);
        metric.setValue(new BigDecimal("1.500000"));
        metric.setUnit("hours");
        metric.setCalculationVersion("test-v1");
        entityManager.persist(metric);

        RiskPrediction prediction = new RiskPrediction();
        prediction.setWorkspace(workspace);
        prediction.setRepository(repository);
        prediction.setPullRequest(pullRequest);
        prediction.setFeature(feature);
        prediction.setModelName("test-model");
        prediction.setModelVersion("test-v1");
        prediction.setRiskScore(new BigDecimal("0.250000"));
        prediction.setRiskLevel(RiskLevel.LOW);
        entityManager.persist(prediction);

        UserActionToken actionToken = new UserActionToken();
        actionToken.setUser(managerUser);
        actionToken.setPurpose(ActionTokenPurpose.VERIFY_EMAIL);
        actionToken.setTokenHash("action-token-hash");
        actionToken.setExpiresAt(now.plusSeconds(300));
        entityManager.persist(actionToken);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(managerUser);
        refreshToken.setFamilyId(UUID.randomUUID());
        refreshToken.setTokenHash("refresh-token-hash");
        refreshToken.setExpiresAt(now.plusSeconds(3_600));
        entityManager.persist(refreshToken);

        AuditLog auditLog = new AuditLog();
        auditLog.setWorkspace(workspace);
        auditLog.setActorUser(managerUser);
        auditLog.setActorMembership(manager);
        auditLog.setAction("defaults.tested");
        auditLog.setEntityType("workspace");
        entityManager.persist(auditLog);

        JiraIntegration jira = jiraIntegration(workspace, manager, now);
        entityManager.persist(jira);

        JiraProject jiraProject = new JiraProject();
        jiraProject.setWorkspace(workspace);
        jiraProject.setJiraIntegration(jira);
        jiraProject.setJiraProjectId("10000");
        jiraProject.setProjectKey("ADEPT");
        jiraProject.setProjectName("Adept Test");
        entityManager.persist(jiraProject);

        JiraIssue jiraIssue = new JiraIssue();
        jiraIssue.setWorkspace(workspace);
        jiraIssue.setJiraProject(jiraProject);
        jiraIssue.setJiraIssueId("10001");
        jiraIssue.setIssueKey("ADEPT-1");
        jiraIssue.setSummary("Persistence defaults test");
        entityManager.persist(jiraIssue);

        Deployment deployment = new Deployment();
        deployment.setWorkspace(workspace);
        deployment.setRepository(repository);
        deployment.setSource(DeploymentSource.MANUAL);
        deployment.setExternalDeploymentId("deployment-defaults-test");
        deployment.setEnvironment("production");
        deployment.setStatus(DeploymentStatus.SUCCESS);
        deployment.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        entityManager.persist(deployment);

        entityManager.flush();

        assertThat(manager.getJoinedAt()).isNotNull();
        assertThat(github.getInstalledAt()).isNotNull();
        assertThat(repository.getDefaultBranch()).isEqualTo("main");
        assertThat(repository.getVisibility()).isEqualTo(RepositoryVisibility.PRIVATE);
        assertThat(assignment.getAssignedAt()).isNotNull();
        assertThat(oauthState.getRedirectPath()).isEqualTo("/dashboard/integrations");
        assertThat(oauthState.getCreatedAt()).isNotNull();
        assertThat(rawEvent.getReceivedAt()).isNotNull();
        assertThat(job.getAvailableAt()).isNotNull();
        assertThat(pullRequest.getLastSyncedAt()).isNotNull();
        assertThat(feature.getExtractedAt()).isNotNull();
        assertThat(metric.getCalculatedAt()).isNotNull();
        assertThat(prediction.getPredictedAt()).isNotNull();
        assertThat(actionToken.getCreatedAt()).isNotNull();
        assertThat(refreshToken.getCreatedAt()).isNotNull();
        assertThat(auditLog.getCreatedAt()).isNotNull();

        RepositoryJiraProject repositoryProject = new RepositoryJiraProject();
        repositoryProject.setRepository(repository);
        repositoryProject.setJiraProject(jiraProject);
        entityManager.persist(repositoryProject);

        DeploymentPullRequest deploymentPullRequest = new DeploymentPullRequest();
        deploymentPullRequest.setDeployment(deployment);
        deploymentPullRequest.setPullRequest(pullRequest);
        deploymentPullRequest.setLinkMethod(DeploymentLinkMethod.MANUAL);
        entityManager.persist(deploymentPullRequest);

        PullRequestIssueLink issueLink = new PullRequestIssueLink();
        issueLink.setId(new PullRequestIssueLinkId(pullRequest.getId(), jiraIssue.getId()));
        issueLink.setPullRequest(pullRequest);
        issueLink.setJiraIssue(jiraIssue);
        issueLink.setLinkSource(IssueLinkSource.MANUAL);
        entityManager.persist(issueLink);

        entityManager.flush();

        assertThat(repositoryProject.getCreatedAt()).isNotNull();
        assertThat(deploymentPullRequest.getCreatedAt()).isNotNull();
        assertThat(issueLink.getConfidence()).isEqualTo(1.0);
        assertThat(issueLink.getCreatedAt()).isNotNull();
    }

    private User user(String email, String displayName) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("test-password-hash");
        user.setDisplayName(displayName);
        return user;
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setName("Adept Test");
        workspace.setSlug("adept-defaults-test");
        return workspace;
    }

    private Membership membership(Workspace workspace, User user, MembershipRole role) {
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setUser(user);
        membership.setRole(role);
        return membership;
    }

    private GithubIntegration githubIntegration(Workspace workspace, Membership manager) {
        GithubIntegration integration = new GithubIntegration();
        integration.setWorkspace(workspace);
        integration.setInstallationId(10_001L);
        integration.setAccountExternalId(20_001L);
        integration.setAccountLogin("adept-test");
        integration.setAccountType(GithubAccountType.ORGANIZATION);
        integration.setRepositorySelection(RepositorySelection.ALL);
        integration.setInstalledBy(manager);
        return integration;
    }

    private GitRepository gitRepository(Workspace workspace, GithubIntegration github) {
        GitRepository repository = new GitRepository();
        repository.setWorkspace(workspace);
        repository.setGithubIntegration(github);
        repository.setGithubRepoId(30_001L);
        repository.setOwnerLogin("adept-test");
        repository.setName("defaults-test");
        repository.setFullName("adept-test/defaults-test");
        return repository;
    }

    private PullRequest pullRequest(
        Workspace workspace,
        GitRepository repository,
        Instant openedAt
    ) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setWorkspace(workspace);
        pullRequest.setRepository(repository);
        pullRequest.setGithubPrId(40_001L);
        pullRequest.setNumber(1);
        pullRequest.setTitle("Test persistence defaults");
        pullRequest.setState(PullRequestState.OPEN);
        pullRequest.setBaseRef("main");
        pullRequest.setHeadRef("feature/defaults-test");
        pullRequest.setOpenedAt(openedAt);
        return pullRequest;
    }

    private JiraIntegration jiraIntegration(
        Workspace workspace,
        Membership manager,
        Instant now
    ) {
        JiraIntegration integration = new JiraIntegration();
        integration.setWorkspace(workspace);
        integration.setCloudId("jira-cloud-defaults-test");
        integration.setSiteUrl("https://adept-test.atlassian.net");
        integration.setDisplayName("Adept Test Jira");
        integration.setAccessTokenEnc("encrypted-access-token");
        integration.setRefreshTokenEnc("encrypted-refresh-token");
        integration.setEncryptionKeyVersion(1);
        integration.setAccessTokenExpiresAt(now.plusSeconds(3_600));
        integration.setConnectedBy(manager);
        return integration;
    }
}
