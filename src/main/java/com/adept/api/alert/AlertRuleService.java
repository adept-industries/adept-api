package com.adept.api.alert;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.alert.dto.AlertRuleResponse;
import com.adept.api.alert.dto.CreateAlertRuleRequest;
import com.adept.api.alert.dto.UpdateAlertRuleRequest;
import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.domain.AlertComparator;
import com.adept.api.common.domain.AlertMetricType;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.NotificationChannel;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;

import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;

@Service
@Transactional
public class AlertRuleService {

    private final AlertRuleRepository alertRuleRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final RepositoryScopeService repositoryScopeService;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ProcessingJobRepository processingJobRepository;

    public AlertRuleService(
            AlertRuleRepository alertRuleRepository,
            GitRepositoryRepository gitRepositoryRepository,
            RepositoryScopeService repositoryScopeService,
            MembershipRepository membershipRepository,
            UserRepository userRepository,
            AuditService auditService,
            ProcessingJobRepository processingJobRepository) {
        this.alertRuleRepository = alertRuleRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.repositoryScopeService = repositoryScopeService;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.processingJobRepository = processingJobRepository;
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> list(AuthenticatedPrincipal principal, UUID repositoryId) {
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ProblemCode.WORKSPACE_FORBIDDEN);
        }

        if (repositoryId != null) {
            // Must be readable by caller (Manager or assigned Lead)
            repositoryScopeService.requireReadableRepository(principal, repositoryId);
            return alertRuleRepository.findAllByWorkspaceIdAndRepositoryId(principal.workspaceId(), repositoryId)
                .stream()
                .map(AlertRuleResponse::from)
                .toList();
        }

        if (principal.role() == MembershipRole.MANAGER) {
            return alertRuleRepository.findAllByWorkspaceId(principal.workspaceId())
                .stream()
                .map(AlertRuleResponse::from)
                .toList();
        }

        // Lead: only rules for readable (assigned, trackingEnabled, non-archived) repositories
        List<UUID> leadRepoIds = gitRepositoryRepository.findAllLeadReadableRepositories(
                principal.workspaceId(),
                principal.membershipId()
            )
            .stream()
            .map(GitRepository::getId)
            .toList();

        if (leadRepoIds.isEmpty()) {
            return Collections.emptyList();
        }

        return alertRuleRepository.findAllByWorkspaceIdAndRepositoryIdIn(principal.workspaceId(), leadRepoIds)
            .stream()
            .map(AlertRuleResponse::from)
            .toList();
    }

    public AlertRuleResponse create(
            AuthenticatedPrincipal principal,
            CreateAlertRuleRequest request,
            AccountRequestContext context) {
        Membership currentMembership = requireCurrentMembership(principal);
        User currentUser = currentMembership.getUser();

        // Check repository accessibility according to role (Manager: any; Lead: assigned)
        GitRepository repository = repositoryScopeService.requireReadableRepository(principal, request.repositoryId());

        String destination = request.destination();
        if (destination == null || destination.isBlank()) {
            destination = currentUser.getEmail();
        } else {
            destination = destination.trim().toLowerCase(java.util.Locale.ROOT);
            validateDestinationForRole(destination, currentUser, principal);
        }

        AlertRule rule = new AlertRule();
        rule.setWorkspace(currentMembership.getWorkspace());
        rule.setRepository(repository);
        rule.setCreatedBy(currentMembership);
        rule.setName(request.name().trim());
        rule.setMetricType(request.metricType());
        rule.setComparator(request.comparator());
        rule.setThresholdValue(request.thresholdValue());
        rule.setEvaluationWindowMinutes(
            request.evaluationWindowMinutes() != null ? request.evaluationWindowMinutes() : 1440
        );
        rule.setCooldownMinutes(
            request.cooldownMinutes() != null ? request.cooldownMinutes() : 1440
        );
        rule.setChannel(request.channel() != null ? request.channel() : NotificationChannel.EMAIL);
        rule.setDestination(destination);
        rule.setEnabled(request.enabled() != null ? request.enabled() : true);

        AlertRule saved = alertRuleRepository.save(rule);

        if (saved.isEnabled()) {
            enqueueAlertEvaluation(saved, "ALERT_RULE_CREATED");
        }

        audit(AuditAction.ALERT_RULE_CREATED, saved, currentMembership, Map.of(
            "name", saved.getName(),
            "repositoryId", repository.getId().toString(),
            "metricType", saved.getMetricType().name(),
            "comparator", saved.getComparator().name(),
            "thresholdValue", saved.getThresholdValue().toString(),
            "destination", saved.getDestination()
        ), context);

        return AlertRuleResponse.from(saved);
    }

    public AlertRuleResponse update(
            AuthenticatedPrincipal principal,
            UUID ruleId,
            UpdateAlertRuleRequest request,
            AccountRequestContext context) {
        request.validate();
        Membership currentMembership = requireCurrentMembership(principal);
        User currentUser = currentMembership.getUser();
        AlertRule rule = requireAlertRule(ruleId, principal.workspaceId());

        // Scope check: rule's repository must be readable by caller
        repositoryScopeService.requireReadableRepository(principal, rule.getRepository().getId());

        // Ownership/Manager check: Owner or Manager
        requireOwnerOrManager(rule, principal);

        Map<String, Object> changedFields = new LinkedHashMap<>();

        if (request.isNamePresent()) {
            rule.setName(request.getName().trim());
            changedFields.put("name", rule.getName());
        }
        if (request.isMetricTypePresent()) {
            rule.setMetricType(request.getMetricType());
            changedFields.put("metricType", rule.getMetricType().name());
        }
        if (request.isComparatorPresent()) {
            rule.setComparator(request.getComparator());
            changedFields.put("comparator", rule.getComparator().name());
        }
        if (request.isThresholdValuePresent()) {
            rule.setThresholdValue(request.getThresholdValue());
            changedFields.put("thresholdValue", rule.getThresholdValue().toString());
        }
        if (request.isEvaluationWindowMinutesPresent()) {
            rule.setEvaluationWindowMinutes(request.getEvaluationWindowMinutes());
            changedFields.put("evaluationWindowMinutes", rule.getEvaluationWindowMinutes());
        }
        if (request.isCooldownMinutesPresent()) {
            rule.setCooldownMinutes(request.getCooldownMinutes());
            changedFields.put("cooldownMinutes", rule.getCooldownMinutes());
        }
        if (request.isChannelPresent()) {
            rule.setChannel(request.getChannel());
            changedFields.put("channel", rule.getChannel().name());
        }
        if (request.isDestinationPresent()) {
            String destination = request.getDestination().trim().toLowerCase(java.util.Locale.ROOT);
            validateDestinationForRole(destination, currentUser, principal);
            rule.setDestination(destination);
            changedFields.put("destination", rule.getDestination());
        }
        if (request.isEnabledPresent()) {
            rule.setEnabled(request.getEnabled());
            changedFields.put("enabled", rule.isEnabled());
        }

        AlertRule updated = alertRuleRepository.save(rule);

        if (updated.isEnabled()) {
            enqueueAlertEvaluation(updated, "ALERT_RULE_UPDATED");
        }

        audit(AuditAction.ALERT_RULE_UPDATED, updated, currentMembership, Map.of(
            "changedFields", changedFields
        ), context);

        return AlertRuleResponse.from(updated);
    }

    private void enqueueAlertEvaluation(AlertRule rule, String triggerSource) {
        if (rule.getRepository() == null) {
            return;
        }
        boolean pendingJobExists = processingJobRepository.existsByRepository_IdAndJobTypeAndStatusIn(
            rule.getRepository().getId(),
            ProcessingJobType.EVALUATE_ALERTS,
            List.of(ProcessingJobStatus.PENDING)
        );
        if (!pendingJobExists) {
            ProcessingJob job = new ProcessingJob();
            job.setWorkspace(rule.getWorkspace());
            job.setRepository(rule.getRepository());
            job.setJobType(ProcessingJobType.EVALUATE_ALERTS);
            job.setStatus(ProcessingJobStatus.PENDING);
            job.setPriority(100);
            job.setAvailableAt(java.time.Instant.now());
            job.setPayload(Map.of(
                "workspace_id", rule.getWorkspace().getId().toString(),
                "repository_id", rule.getRepository().getId().toString(),
                "trigger_source", triggerSource
            ));
            processingJobRepository.save(job);
        }
    }

    public void delete(
            AuthenticatedPrincipal principal,
            UUID ruleId,
            AccountRequestContext context) {
        Membership currentMembership = requireCurrentMembership(principal);
        AlertRule rule = requireAlertRule(ruleId, principal.workspaceId());

        // Scope check: rule's repository must be readable by caller
        repositoryScopeService.requireReadableRepository(principal, rule.getRepository().getId());

        // Ownership/Manager check: Owner or Manager
        requireOwnerOrManager(rule, principal);

        audit(AuditAction.ALERT_RULE_DELETED, rule, currentMembership, Map.of(
            "name", rule.getName(),
            "repositoryId", rule.getRepository().getId().toString()
        ), context);

        alertRuleRepository.delete(rule);
    }

    private void validateDestinationForRole(
            String destination,
            User currentUser,
            AuthenticatedPrincipal principal) {
        if (principal.role() == MembershipRole.MANAGER) {
            return;
        }

        // Lead: A Lead cannot send alerts to arbitrary bulk recipient lists.
        // Allowed destinations for Lead: own email, or a verified active member in the workspace.
        if (destination.equalsIgnoreCase(currentUser.getEmail())) {
            return;
        }

        // Check if destination belongs to an active workspace member
        boolean isWorkspaceMember = userRepository.findByEmailIgnoreCase(destination)
            .flatMap(user -> membershipRepository.findByWorkspaceIdAndUserId(principal.workspaceId(), user.getId()))
            .filter(membership -> membership.getStatus() == com.adept.api.common.domain.MembershipStatus.ACTIVE)
            .isPresent();

        if (!isWorkspaceMember) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "Leads cannot set alert destinations outside of active workspace members."
            );
        }
    }

    private AlertRule requireAlertRule(UUID ruleId, UUID workspaceId) {
        if (ruleId == null) {
            throw new NotFoundException(ProblemCode.ALERT_RULE_NOT_FOUND);
        }
        return alertRuleRepository.findByIdAndWorkspaceId(ruleId, workspaceId)
            .orElseThrow(() -> new NotFoundException(ProblemCode.ALERT_RULE_NOT_FOUND));
    }

    private void requireOwnerOrManager(AlertRule rule, AuthenticatedPrincipal principal) {
        if (principal.role() == MembershipRole.MANAGER) {
            return;
        }
        if (rule.getCreatedBy() != null && rule.getCreatedBy().getId().equals(principal.membershipId())) {
            return;
        }
        throw new ForbiddenException(ProblemCode.ALERT_RULE_FORBIDDEN);
    }

    private Membership requireCurrentMembership(AuthenticatedPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.workspaceId() == null) {
            throw new ApiException(ProblemCode.WORKSPACE_FORBIDDEN);
        }
        return membershipRepository.findActiveByUserIdAndWorkspaceId(principal.userId(), principal.workspaceId())
            .filter(membership -> membership.getId().equals(principal.membershipId()))
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));
    }

    private void audit(
            AuditAction action,
            AlertRule rule,
            Membership membership,
            Map<String, Object> metadata,
            AccountRequestContext context) {
        auditService.record(
            action,
            membership.getUser(),
            membership,
            membership.getWorkspace(),
            "ALERT_RULE",
            rule.getId(),
            metadata,
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );
    }
}
