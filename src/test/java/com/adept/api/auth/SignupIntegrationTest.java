package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import com.adept.api.workspace.WorkspaceRepository;
import com.adept.api.workspace.WorkspaceSlugService;

class SignupIntegrationTest {

    @Test
    void workspaceSlugUsesCleanBaseRandomSuffixAndLengthLimit() {
        WorkspaceRepository repository = (WorkspaceRepository) Proxy.newProxyInstance(
            WorkspaceRepository.class.getClassLoader(),
            new Class<?>[] { WorkspaceRepository.class },
            (proxy, method, args) -> method.getName().equals("existsBySlug") ? false : null
        );
        WorkspaceSlugService service = new WorkspaceSlugService(repository);

        String slug = service.generate("  Adept Phase 2: Auth & Workspace Launch!!!  ");

        assertThat(slug)
            .startsWith("adept-phase-2-auth-workspace-launch-")
            .matches("^[a-z0-9-]+-[0-9a-f]{8}$")
            .hasSizeLessThanOrEqualTo(80);
    }

    @Test
    void signupRequestToStringRedactsCredentials() {
        var request = new com.adept.api.auth.dto.SignupRequest(
            "alice@example.com",
            "super-secret-password",
            "Alice",
            "Alice Workspace",
            "UTC"
        );

        assertThat(request.toString())
            .doesNotContain("alice@example.com", "super-secret-password", "Alice Workspace")
            .contains("<redacted>");
    }
}
