package com.adept.api.devimport;

import java.util.List;
import java.util.Map;

record DevelopmentGithubImportPayload(
    Map<String, Object> repository,
    List<Map<String, Object>> contributors,
    List<DevelopmentGithubPullRequestImport> pullRequests,
    List<Map<String, Object>> workflowRuns
) {
}

record DevelopmentGithubPullRequestImport(
    Map<String, Object> pullRequest,
    List<Map<String, Object>> commits,
    List<Map<String, Object>> reviews,
    List<Map<String, Object>> comments,
    List<Map<String, Object>> files
) {
}

record DevelopmentGithubImportResult(
    String workspaceName,
    String workspaceSlug,
    String projectName,
    String repository,
    String managerEmail,
    String leadEmail,
    String coLeadEmail,
    int contributorsSeen,
    int pullRequestsCreated,
    int pullRequestsUpdated,
    int featuresCreated,
    int featuresUpdated,
    int workflowDeploymentsCreated,
    int workflowDeploymentsUpdated,
    boolean recalculateMetricsJobQueued,
    boolean removedDemoData
) {
}
