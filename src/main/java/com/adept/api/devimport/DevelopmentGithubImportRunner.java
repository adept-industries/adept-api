package com.adept.api.devimport;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Profile("local")
@Component
@ConditionalOnProperty(name = "app.dev.github-import.enabled", havingValue = "true")
class DevelopmentGithubImportRunner implements ApplicationRunner {

    private final DevelopmentGithubImportService importService;
    private final Environment environment;
    private final ConfigurableApplicationContext applicationContext;

    DevelopmentGithubImportRunner(
            DevelopmentGithubImportService importService,
            Environment environment,
            ConfigurableApplicationContext applicationContext) {
        this.importService = importService;
        this.environment = environment;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            DevelopmentGithubImportOptions options = DevelopmentGithubImportOptions.from(args, environment);
            DevelopmentGithubImportResult result = options.removeDemoData()
                ? importService.removeDemoData(options)
                : importService.importRepository(options);
            printResult(result, options.demoPassword());
        } finally {
            applicationContext.close();
        }
    }

    private static void printResult(DevelopmentGithubImportResult result, String demoPassword) {
        if (result.removedDemoData()) {
            System.out.println("Removed GitHub demo data for workspace slug: " + result.workspaceSlug());
            return;
        }

        System.out.println();
        System.out.println("GitHub demo import complete");
        System.out.println("Workspace: " + result.workspaceName() + " (" + result.workspaceSlug() + ")");
        System.out.println("Project: " + result.projectName());
        System.out.println("Repository: " + result.repository());
        System.out.println("Contributors seen: " + result.contributorsSeen());
        System.out.println("Pull requests created/updated: "
            + result.pullRequestsCreated() + "/" + result.pullRequestsUpdated());
        System.out.println("PR features created/updated: "
            + result.featuresCreated() + "/" + result.featuresUpdated());
        System.out.println("Workflow deployments created/updated: "
            + result.workflowDeploymentsCreated() + "/" + result.workflowDeploymentsUpdated());
        System.out.println("Recalculate metrics job queued: " + result.recalculateMetricsJobQueued());
        System.out.println();
        System.out.println("Demo login accounts");
        System.out.println("Manager: " + result.managerEmail());
        System.out.println("Lead: " + result.leadEmail());
        System.out.println("Co-Lead: " + result.coLeadEmail());
        System.out.println("Password: " + demoPassword);
        System.out.println();
    }
}
