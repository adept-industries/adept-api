package com.adept.api.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMailServiceTest {

    @Test
    void resolvesDashboardLinksInPlainTextAndHtmlAgainstTheConfiguredFrontend() {
        String content = """
            View Dashboard: http://localhost:5173/dashboard
            <a href="http://localhost:5173/dashboard?repository=repo-1">Go to Dashboard</a>
            """;

        String resolved = AccountMailService.resolveAlertDashboardLink(
            content,
            "https://adept.example"
        );

        assertThat(resolved)
            .contains("https://adept.example/dashboard")
            .contains("https://adept.example/dashboard?repository=repo-1")
            .doesNotContain("localhost:5173");
    }

    @Test
    void leavesNonDashboardLinksUntouched() {
        String content = "Documentation: http://localhost:5173/docs";

        assertThat(AccountMailService.resolveAlertDashboardLink(
            content,
            "https://adept.example"
        )).isEqualTo(content);
    }
}
