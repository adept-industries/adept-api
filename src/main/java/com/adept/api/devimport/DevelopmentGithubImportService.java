package com.adept.api.devimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("local")
@Service
class DevelopmentGithubImportService {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentGithubImportService.class);

    private final DevelopmentGithubApiClient github;
    private final DevelopmentGithubImportPersistenceService persistence;

    DevelopmentGithubImportService(
            DevelopmentGithubApiClient github,
            DevelopmentGithubImportPersistenceService persistence) {
        this.github = github;
        this.persistence = persistence;
    }

    DevelopmentGithubImportResult importRepository(DevelopmentGithubImportOptions options) {
        String owner = options.owner();
        String repo = options.repoName();
        log.info(
            "development_github_import_fetching_repository repository={} maxPullRequests={} maxWorkflowRuns={}",
            options.repository(),
            options.maxPullRequests(),
            options.maxWorkflowRuns()
        );

        Map<String, Object> repository = github.getRepository(owner, repo);
        List<Map<String, Object>> contributors = github.listContributors(owner, repo);
        List<Map<String, Object>> pullRequestSummaries =
            github.listPullRequests(owner, repo, options.maxPullRequests());

        List<DevelopmentGithubPullRequestImport> pullRequests = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> summary : pullRequestSummaries) {
            index++;
            int number = intValue(summary.get("number"));
            if (index == 1 || index % 25 == 0 || index == pullRequestSummaries.size()) {
                log.info(
                    "development_github_import_fetching_pull_requests repository={} progress={}/{}",
                    options.repository(),
                    index,
                    pullRequestSummaries.size()
                );
            }
            Map<String, Object> detail = github.getPullRequest(owner, repo, number);
            pullRequests.add(new DevelopmentGithubPullRequestImport(
                detail,
                github.listPullRequestCommits(owner, repo, number),
                github.listPullRequestReviews(owner, repo, number),
                github.listIssueComments(owner, repo, number),
                github.listPullRequestFiles(owner, repo, number)
            ));
        }

        List<Map<String, Object>> workflowRuns = options.maxWorkflowRuns() == 0
            ? List.of()
            : github.listWorkflowRuns(owner, repo, options.maxWorkflowRuns());

        DevelopmentGithubImportPayload payload =
            new DevelopmentGithubImportPayload(repository, contributors, pullRequests, workflowRuns);
        return persistence.persist(options, payload);
    }

    DevelopmentGithubImportResult removeDemoData(DevelopmentGithubImportOptions options) {
        return persistence.removeDemoData(options);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
