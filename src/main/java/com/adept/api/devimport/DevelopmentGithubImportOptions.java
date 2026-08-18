package com.adept.api.devimport;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;

record DevelopmentGithubImportOptions(
    String repository,
    int maxPullRequests,
    int maxWorkflowRuns,
    String workspaceName,
    String workspaceSlug,
    String projectName,
    String managerEmail,
    String leadEmail,
    String coLeadEmail,
    String demoPassword,
    boolean removeDemoData
) {

    private static final Pattern REPOSITORY_PATTERN =
        Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern SLUG_PATTERN =
        Pattern.compile("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$");

    static DevelopmentGithubImportOptions from(ApplicationArguments arguments, Environment environment) {
        boolean removeDemoData = optionBoolean(arguments, "adept.dev-import.remove-demo-data", false);
        String repository = option(arguments, "adept.dev-import.repository", "");
        if (!removeDemoData && !REPOSITORY_PATTERN.matcher(repository).matches()) {
            throw new IllegalArgumentException("Repository must be formatted as owner/name, for example tiangolo/fastapi.");
        }

        int maxPullRequests = optionInt(arguments, "adept.dev-import.max-prs", 100, 1, 1000);
        int maxWorkflowRuns = optionInt(arguments, "adept.dev-import.max-workflow-runs", 50, 0, 1000);
        String workspaceName = optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.workspace-name",
            "ADEPT_DEMO_WORKSPACE_NAME",
            "GitHub Demo Data"
        );
        String workspaceSlug = optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.workspace-slug",
            "ADEPT_DEMO_WORKSPACE_SLUG",
            "github-demo-data"
        ).toLowerCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(workspaceSlug).matches()) {
            throw new IllegalArgumentException("Workspace slug must use lowercase letters, numbers, and hyphens.");
        }

        String projectName = optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.project-name",
            "ADEPT_DEMO_PROJECT_NAME",
            "Imported Public Repositories"
        );
        String managerEmail = normalizedEmail(optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.manager-email",
            "ADEPT_DEMO_MANAGER_EMAIL",
            "demo.manager@adept.local"
        ));
        String leadEmail = normalizedEmail(optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.lead-email",
            "ADEPT_DEMO_LEAD_EMAIL",
            "demo.lead@adept.local"
        ));
        String coLeadEmail = normalizedEmail(optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.colead-email",
            "ADEPT_DEMO_COLEAD_EMAIL",
            "demo.colead@adept.local"
        ));
        String demoPassword = optionOrEnv(
            arguments,
            environment,
            "adept.dev-import.demo-password",
            "ADEPT_DEMO_PASSWORD",
            "AdeptDemoPass123!"
        );

        return new DevelopmentGithubImportOptions(
            repository,
            maxPullRequests,
            maxWorkflowRuns,
            workspaceName,
            workspaceSlug,
            projectName,
            managerEmail,
            leadEmail,
            coLeadEmail,
            demoPassword,
            removeDemoData
        );
    }

    String owner() {
        return repository.split("/", 2)[0];
    }

    String repoName() {
        return repository.split("/", 2)[1];
    }

    private static String optionOrEnv(
            ApplicationArguments arguments,
            Environment environment,
            String optionName,
            String envName,
            String defaultValue) {
        String value = option(arguments, optionName, null);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = environment.getProperty(envName);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return defaultValue;
    }

    private static String option(ApplicationArguments arguments, String name, String defaultValue) {
        if (!arguments.containsOption(name)) {
            return defaultValue;
        }
        return arguments.getOptionValues(name).stream()
            .findFirst()
            .orElse(defaultValue);
    }

    private static int optionInt(
            ApplicationArguments arguments,
            String name,
            int defaultValue,
            int min,
            int max) {
        String value = option(arguments, name, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number.", exception);
        }
    }

    private static boolean optionBoolean(ApplicationArguments arguments, String name, boolean defaultValue) {
        String value = option(arguments, name, Boolean.toString(defaultValue));
        return Boolean.parseBoolean(value);
    }

    private static String normalizedEmail(String value) {
        if (value == null || value.isBlank() || !value.contains("@")) {
            throw new IllegalArgumentException("Demo user emails must be valid email-like values.");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
