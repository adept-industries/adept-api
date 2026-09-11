package com.adept.api.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

class GithubRepositoryOptionsClientTest {
    private final GithubAppTokenService tokens = mock(GithubAppTokenService.class);
    private MockRestServiceServer server;
    private GithubRepositoryOptionsClient client;

    @BeforeEach
    void setUp() {
        var builder = GithubRepositoryOptionsClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubRepositoryOptionsClient(tokens, builder.build());
        when(tokens.getInstallationToken(42)).thenReturn("private-token");
    }

    @Test
    void paginatesDeduplicatesAndPreservesActualNames() {
        server.expect(requestTo(url("branches", 1))).andExpect(header("Authorization", "Bearer private-token"))
            .andRespond(withSuccess("[{\"name\":\"release/next\"},{\"name\":\"main\"}]", MediaType.APPLICATION_JSON)
                .header("Link", "<https://untrusted.example/never-follow>; rel=\"next\""));
        server.expect(requestTo(url("branches", 2)))
            .andRespond(withSuccess("[{\"name\":\"main\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("actions/workflows", 1)))
            .andRespond(withSuccess("{\"total_count\":1,\"workflows\":[{\"name\":\"CI [prod], deploy\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("environments", 1)))
            .andRespond(withSuccess("{\"total_count\":1,\"environments\":[{\"name\":\"production\"}]}", MediaType.APPLICATION_JSON));

        var result = client.discover(42, "acme", "app");
        assertThat(result.complete()).isTrue();
        assertThat(result.branches().values()).containsExactly("main", "release/next");
        assertThat(result.workflows().values()).containsExactly("CI [prod], deploy");
        assertThat(result.environments().values()).containsExactly("production");
        server.verify();
    }

    @Test
    void permissionFailureDoesNotHideOtherListsOrLeakProviderDetails() {
        server.expect(requestTo(url("branches", 1)))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("private-token confidential details"));
        server.expect(requestTo(url("actions/workflows", 1)))
            .andRespond(withSuccess("{\"total_count\":0,\"workflows\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("environments", 1)))
            .andRespond(withSuccess("{\"environments\":[{\"name\":\"prod\"}]}", MediaType.APPLICATION_JSON));
        var result = client.discover(42, "acme", "app");
        assertThat(result.branches().complete()).isFalse();
        assertThat(result.branches().warning()).contains("read permissions").doesNotContain("private-token", "confidential");
        assertThat(result.workflows().complete()).isTrue();
        assertThat(result.workflows().values()).isEmpty();
        assertThat(result.environments().values()).containsExactly("prod");
        server.verify();
    }

    @Test
    void retainsFetchedPagesOnLaterFailureAndFlagsMalformedCollections() {
        server.expect(requestTo(url("branches", 1)))
            .andRespond(withSuccess("[{\"name\":\"main\"}]", MediaType.APPLICATION_JSON)
                .header("Link", "<next>; rel=\"next\""));
        server.expect(requestTo(url("branches", 2))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url("actions/workflows", 1))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("environments", 1)))
            .andRespond(withSuccess("{\"environments\":[{\"bad\":true},{\"name\":\"prod\"}]}", MediaType.APPLICATION_JSON));
        var result = client.discover(42, "acme", "app");
        assertThat(result.branches().values()).containsExactly("main");
        assertThat(result.branches().complete()).isFalse();
        assertThat(result.workflows().complete()).isFalse();
        assertThat(result.environments().complete()).isFalse();
        assertThat(result.environments().values()).containsExactly("prod");
        server.verify();
    }

    @Test
    void boundsPaginationAndReportsPartialResult() {
        for (int page = 1; page <= GithubRepositoryOptionsClient.MAX_PAGES; page++) {
            server.expect(requestTo(url("branches", page)))
                .andRespond(withSuccess("[{\"name\":\"branch-" + page + "\"}]", MediaType.APPLICATION_JSON)
                    .header("Link", "<next>; rel=\"next\""));
        }
        server.expect(requestTo(url("actions/workflows", 1))).andRespond(withSuccess("{\"workflows\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("environments", 1))).andRespond(withSuccess("{\"environments\":[]}", MediaType.APPLICATION_JSON));
        var result = client.discover(42, "acme", "app");
        assertThat(result.branches().values()).hasSize(GithubRepositoryOptionsClient.MAX_PAGES);
        assertThat(result.branches().warning()).contains("500");
        assertThat(result.branches().complete()).isFalse();
        server.verify();
    }

    @Test
    void tokenFailureReturnsManualFallbackWithoutLeakingErrors() {
        when(tokens.getInstallationToken(42)).thenThrow(new IllegalStateException("private-token"));
        var result = client.discover(42, "acme", "app");
        assertThat(result.complete()).isFalse();
        assertThat(result.branches().warning()).doesNotContain("private-token");
        assertThat(result.branches().values()).isEmpty();
        server.verify();
    }

    private String url(String path, int page) {
        return "https://api.github.com/repos/acme/app/" + path + "?per_page=100&page=" + page;
    }
}
