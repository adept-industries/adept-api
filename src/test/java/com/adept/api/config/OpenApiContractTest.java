package com.adept.api.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.PartCIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "springdoc.api-docs.enabled=true",
    "springdoc.swagger-ui.enabled=true"
})
@ActiveProfiles("test")
class OpenApiContractTest extends PartCIntegrationTestSupport {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private static final List<Endpoint> ENDPOINTS = List.of(
        endpoint("/api/v1/auth/csrf", "get", "getCsrfToken", "204", security(), null),
        endpoint("/api/v1/auth/signup", "post", "signup", "201", security("csrfHeader"), "SignupRequest",
            "400", "403", "409", "413", "415", "429"),
        endpoint("/api/v1/auth/verify-email", "post", "verifyEmail", "204", security("csrfHeader"), "ActionTokenRequest",
            "400", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/resend-verification", "post", "resendVerification", "202", security("csrfHeader"), "EmailRequest",
            "400", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/login", "post", "login", "200", security("csrfHeader"), "LoginRequest",
            "400", "401", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/reauthenticate/password", "post", "reauthenticateWithPassword", "200",
            security("bearerAuth", "csrfHeader"), "PasswordReauthenticationRequest",
            "400", "401", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/refresh", "post", "refreshSession", "200", security("refreshCookie", "csrfHeader"), "RefreshRequest",
            "400", "401", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/logout", "post", "logoutSession", "204", security("csrfHeader"), null,
            "403", "429"),
        endpoint("/api/v1/auth/me", "get", "getCurrentUser", "200", security("bearerAuth"), null,
            "401", "403"),
        endpoint("/api/v1/auth/switch-workspace/{workspaceId}", "post", "switchWorkspace", "200",
            security("refreshCookie", "csrfHeader"), null, "400", "401", "403", "404", "429"),
        endpoint("/api/v1/auth/workspaces", "post", "createWorkspaceForSession", "201",
            security("refreshCookie", "csrfHeader"), "CreateWorkspaceRequest",
            "400", "401", "403", "409", "413", "415", "429"),
        endpoint("/api/v1/auth/forgot-password", "post", "forgotPassword", "202", security("csrfHeader"), "EmailRequest",
            "400", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/reset-password", "post", "resetPassword", "204", security("csrfHeader"), "ResetPasswordRequest",
            "400", "403", "413", "415", "429"),
        endpoint("/api/v1/auth/google/start", "get", "startGoogleAuthentication", "302", security(), null,
            "404", "429"),
        endpoint("/api/v1/auth/google/onboarding", "post", "completeGoogleOnboarding", "200",
            security("oauthSessionCookie", "csrfHeader"), "GoogleOnboardingRequest",
            "400", "401", "403", "409", "413", "415", "429"),
        endpoint("/api/v1/auth/google/reauthentication/start", "post", "startGoogleReauthentication", "200",
            security("bearerAuth", "csrfHeader"), null, "401", "403", "404", "429"),
        endpoint("/api/v1/workspaces", "get", "listWorkspaces", "200", security("bearerAuth"), null,
            "401", "403"),
        endpoint("/api/v1/workspaces", "post", "createWorkspace", "201", security("bearerAuth", "csrfHeader"), "CreateWorkspaceRequest",
            "400", "401", "403", "409", "413", "415"),
        endpoint("/api/v1/workspaces/current", "get", "getCurrentWorkspace", "200", security("bearerAuth"), null,
            "401", "403"),
        endpoint("/api/v1/workspaces/current", "patch", "updateCurrentWorkspace", "200",
            security("bearerAuth", "csrfHeader"), "UpdateWorkspaceRequest",
            "400", "401", "403", "409", "413", "415"),
        endpoint("/api/v1/workspaces/current", "delete", "deleteCurrentWorkspace", "202",
            security("bearerAuth", "csrfHeader"), "DeleteWorkspaceRequest",
            "400", "401", "403", "409", "413", "415", "429"),
        endpoint("/api/v1/workspaces/current/members/lookup", "post", "lookupCurrentWorkspaceMember", "200",
            security("bearerAuth", "csrfHeader"), "LookupWorkspaceMemberRequest",
            "400", "401", "403", "413", "415"),
        endpoint("/api/v1/projects", "get", "listProjects", "200", security("bearerAuth"), null,
            "401", "403"),
        endpoint("/api/v1/projects", "post", "createProject", "201", security("bearerAuth", "csrfHeader"), "CreateProjectRequest",
            "400", "401", "403", "409", "413", "415"),
        endpoint("/api/v1/projects/{projectId}", "get", "getProject", "200", security("bearerAuth"), null,
            "401", "403", "404"),
        endpoint("/api/v1/projects/{projectId}", "patch", "updateProject", "200", security("bearerAuth", "csrfHeader"), "UpdateProjectRequest",
            "400", "401", "403", "404", "409", "413", "415"),
        endpoint("/api/v1/projects/{projectId}", "delete", "deleteProject", "204", security("bearerAuth", "csrfHeader"), null,
            "401", "403", "404"),
        endpoint("/api/v1/projects/{projectId}/repositories", "put", "replaceProjectRepositories", "200",
            security("bearerAuth", "csrfHeader"), "ReplaceProjectRepositoriesRequest",
            "400", "401", "403", "404", "413", "415")
    );

    private static final Map<String, String> SUCCESS_SCHEMAS = Map.ofEntries(
        Map.entry("signup", "#/components/schemas/SignupResponse"),
        Map.entry("login", "#/components/schemas/AuthSessionResponse"),
        Map.entry("reauthenticateWithPassword", "#/components/schemas/AuthSessionResponse"),
        Map.entry("refreshSession", "#/components/schemas/AuthSessionResponse"),
        Map.entry("completeGoogleOnboarding", "#/components/schemas/AuthSessionResponse"),
        Map.entry("startGoogleReauthentication", "#/components/schemas/GoogleReauthenticationStartResponse"),
        Map.entry("getCurrentUser", "#/components/schemas/MeResponse"),
        Map.entry("switchWorkspace", "#/components/schemas/AuthSessionResponse"),
        Map.entry("createWorkspaceForSession", "#/components/schemas/AuthSessionResponse"),
        Map.entry("createWorkspace", "#/components/schemas/WorkspaceSummaryResponse"),
        Map.entry("getCurrentWorkspace", "#/components/schemas/CurrentWorkspaceResponse"),
        Map.entry("updateCurrentWorkspace", "#/components/schemas/CurrentWorkspaceResponse"),
        Map.entry("deleteCurrentWorkspace", "#/components/schemas/WorkspaceDeletionResponse"),
        Map.entry("lookupCurrentWorkspaceMember", "#/components/schemas/CurrentWorkspaceMemberLookupResponse"),
        Map.entry("createProject", "#/components/schemas/ProjectResponse"),
        Map.entry("getProject", "#/components/schemas/ProjectResponse"),
        Map.entry("updateProject", "#/components/schemas/ProjectResponse"),
        Map.entry("replaceProjectRepositories", "#/components/schemas/ProjectResponse")
    );

    private static final Set<String> ALLOWED_SCHEMAS = Set.of(
        "ActionTokenRequest",
        "AuthSessionResponse",
        "AuthenticatedSessionResponse",
        "CurrentWorkspaceResponse",
        "CurrentWorkspaceMemberLookupResponse",
        "CreateProjectRequest",
        "CreateWorkspaceRequest",
        "DeleteWorkspaceRequest",
        "EmailRequest",
        "FieldError",
        "GoogleOnboardingRequest",
        "GoogleReauthenticationStartResponse",
        "LoginRequest",
        "LookupWorkspaceMemberRequest",
        "MeResponse",
        "MembershipSummary",
        "PasswordReauthenticationRequest",
        "ProblemDetail",
        "ProjectRepositoryResponse",
        "ProjectResponse",
        "RefreshRequest",
        "ResetPasswordRequest",
        "ReplaceProjectRepositoriesRequest",
        "SignupRequest",
        "SignupResponse",
        "UpdateWorkspaceRequest",
        "UpdateProjectRequest",
        "UserSummary",
        "WorkspaceDeletionResponse",
        "WorkspaceSelectionSessionResponse",
        "WorkspaceSummaryResponse"
    );

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, true);

    @Test
    void phase2OpenApiContractIsSafeAndMatchesTheCommittedDocument() throws Exception {
        JsonNode live = liveDocument();
        String liveCanonicalJson = mapper.writeValueAsString(canonicalize(live)) + "\n";
        exportOrAssertCommitted(liveCanonicalJson);

        assertPathsOperationsResponsesAndSecurity(live);
        assertSchemasAreSafeAndExpressBothSessionBranches(live);
        assertSecuritySchemes(live);
    }

    private void exportOrAssertCommitted(String liveCanonicalJson) throws Exception {
        String requestedExportPath = System.getProperty("openapi.export.path");
        if (requestedExportPath != null && !requestedExportPath.isBlank()) {
            Path exportPath = Paths.get(requestedExportPath).toAbsolutePath();
            Files.createDirectories(exportPath.getParent());
            Files.writeString(exportPath, liveCanonicalJson);
            return;
        }

        Path fileSpecPath = Paths.get("docs/openapi/adept-api-v1.json").toAbsolutePath();
        assertThat(Files.exists(fileSpecPath))
            .withFailMessage("docs/openapi/adept-api-v1.json must exist")
            .isTrue();

        JsonNode committed = mapper.readTree(Files.readString(fileSpecPath));
        String committedCanonicalJson = mapper.writeValueAsString(canonicalize(committed)) + "\n";
        assertThat(liveCanonicalJson)
            .withFailMessage("Runtime OpenAPI changed; run ./scripts/export-openapi.sh and commit the reviewed contract")
            .isEqualTo(committedCanonicalJson);
    }

    private void assertPathsOperationsResponsesAndSecurity(JsonNode root) {
        JsonNode paths = root.path("paths");

        Set<String> expectedPaths = new TreeSet<>();
        ENDPOINTS.forEach(endpoint -> expectedPaths.add(endpoint.path()));
        assertThat(fieldNames(paths)).isEqualTo(expectedPaths);

        for (Endpoint endpoint : ENDPOINTS) {
            JsonNode operation = paths.path(endpoint.path()).path(endpoint.method());
            assertThat(operation.isMissingNode())
                .withFailMessage("Missing %s %s", endpoint.method(), endpoint.path())
                .isFalse();
            assertThat(operation.path("operationId").asText()).isEqualTo(endpoint.operationId());
            assertSecurity(operation.path("security"), endpoint.securitySchemes());

            Set<String> expectedResponses = new TreeSet<>(endpoint.errorStatuses());
            expectedResponses.add(endpoint.successStatus());
            assertThat(fieldNames(operation.path("responses"))).isEqualTo(expectedResponses);

            endpoint.errorStatuses().forEach(error -> {
                JsonNode response = operation.path("responses").path(error);
                assertThat(fieldNames(response.path("content"))).containsExactly(PROBLEM_JSON);
                assertThat(response.at("/content/application~1problem+json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/ProblemDetail");
            });

            operation.path("responses").forEach(response ->
                assertThat(response.at("/headers/Cache-Control/schema/enum/0").asText()).isEqualTo("no-store")
            );

            if (endpoint.requestSchema() != null) {
                JsonNode requestBody = operation.path("requestBody");
                assertThat(fieldNames(requestBody.path("content"))).containsExactly(JSON);
                assertThat(requestBody.at("/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/" + endpoint.requestSchema());
            } else {
                assertThat(operation.has("requestBody")).isFalse();
            }

            assertSuccessContent(operation, endpoint);
        }

        JsonNode csrf = paths.path("/api/v1/auth/csrf").path("get");
        assertThat(csrf.has("parameters")).isFalse();
        assertThat(csrf.at("/responses/204/headers/Set-Cookie/description").asText()).contains("XSRF-TOKEN");

        JsonNode logout = paths.path("/api/v1/auth/logout").path("post");
        assertThat(logout.path("description").asText()).contains("missing cookie still returns 204");
    }

    private void assertSchemasAreSafeAndExpressBothSessionBranches(JsonNode root) throws Exception {
        JsonNode schemas = root.at("/components/schemas");
        assertThat(fieldNames(schemas)).isEqualTo(new TreeSet<>(ALLOWED_SCHEMAS));

        assertThat(fieldNames(schemas.at("/UpdateWorkspaceRequest/properties")))
            .containsExactly("name", "timezone");
        assertThat(fieldNames(schemas.at("/UpdateProjectRequest/properties")))
            .containsExactly("description", "name");

        assertWriteOnly(schemas, "SignupRequest", "password");
        assertWriteOnly(schemas, "LoginRequest", "password");
        assertWriteOnly(schemas, "PasswordReauthenticationRequest", "password");
        assertWriteOnly(schemas, "ActionTokenRequest", "token");
        assertWriteOnly(schemas, "ResetPasswordRequest", "token");
        assertWriteOnly(schemas, "ResetPasswordRequest", "newPassword");
        assertThat(fieldNames(schemas.at("/DeleteWorkspaceRequest/properties")))
            .containsExactly("confirmationSlug");

        assertThat(schemas.at("/AuthSessionResponse/oneOf/0/$ref").asText())
            .isEqualTo("#/components/schemas/AuthenticatedSessionResponse");
        assertThat(schemas.at("/AuthSessionResponse/oneOf/1/$ref").asText())
            .isEqualTo("#/components/schemas/WorkspaceSelectionSessionResponse");
        assertThat(fieldNames(schemas.at("/AuthenticatedSessionResponse/properties")))
            .contains("accessToken", "expiresInSeconds", "currentMembership");
        assertThat(fieldNames(schemas.at("/WorkspaceSelectionSessionResponse/properties")))
            .doesNotContain("accessToken", "expiresInSeconds", "currentMembership");
        assertThat(schemas.at("/AuthenticatedSessionResponse/properties/workspaceSelectionRequired/enum/0").asBoolean())
            .isFalse();
        assertThat(schemas.at("/WorkspaceSelectionSessionResponse/properties/workspaceSelectionRequired/enum/0").asBoolean())
            .isTrue();

        assertThat(arrayValues(schemas.at("/ProblemDetail/required")))
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code", "traceId");
        assertThat(schemas.at("/ProblemDetail/properties/fieldErrors/items/$ref").asText())
            .isEqualTo("#/components/schemas/FieldError");

        String serializedSchemas = mapper.writeValueAsString(schemas);
        assertThat(serializedSchemas)
            .doesNotContain("passwordHash", "rawToken", "tokenHash", "AuditLog", "ProcessingJob", "GithubIntegration", "JiraIntegration", "GoogleAuthAccount");
    }

    private void assertSecuritySchemes(JsonNode root) {
        JsonNode schemes = root.at("/components/securitySchemes");
        assertThat(fieldNames(schemes)).containsExactly("bearerAuth", "csrfHeader", "oauthSessionCookie", "refreshCookie");
        assertThat(schemes.at("/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(schemes.at("/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.at("/refreshCookie/in").asText()).isEqualTo("cookie");
        assertThat(schemes.at("/refreshCookie/name").asText()).isEqualTo("adept_refresh");
        assertThat(schemes.at("/csrfHeader/in").asText()).isEqualTo("header");
        assertThat(schemes.at("/csrfHeader/name").asText()).isEqualTo("X-XSRF-TOKEN");
        assertThat(schemes.at("/csrfHeader/description").asText()).contains("XSRF-TOKEN cookie");
        assertThat(schemes.at("/oauthSessionCookie/in").asText()).isEqualTo("cookie");
        assertThat(schemes.at("/oauthSessionCookie/name").asText()).isEqualTo("adept_oauth");
    }

    private JsonNode liveDocument() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();
        String rawJson = result.getResponse().getContentAsString();
        assertThat(rawJson).isNotEmpty();
        return mapper.readTree(rawJson);
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = mapper.createObjectNode();
            fieldNames(node).forEach(field -> canonical.set(field, canonicalize(node.get(field))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = mapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return node.deepCopy();
    }

    private static void assertSuccessContent(JsonNode operation, Endpoint endpoint) {
        JsonNode success = operation.path("responses").path(endpoint.successStatus());
        if ("listWorkspaces".equals(endpoint.operationId()) || "listProjects".equals(endpoint.operationId())) {
            assertThat(fieldNames(success.path("content"))).containsExactly(JSON);
            assertThat(success.at("/content/application~1json/schema/type").asText()).isEqualTo("array");
            assertThat(success.at("/content/application~1json/schema/items/$ref").asText())
                .isEqualTo("listWorkspaces".equals(endpoint.operationId())
                    ? "#/components/schemas/WorkspaceSummaryResponse"
                    : "#/components/schemas/ProjectResponse");
            return;
        }

        String expectedSchema = SUCCESS_SCHEMAS.get(endpoint.operationId());
        if (expectedSchema == null) {
            assertThat(success.has("content")).isFalse();
        } else {
            assertThat(fieldNames(success.path("content"))).containsExactly(JSON);
            assertThat(success.at("/content/application~1json/schema/$ref").asText()).isEqualTo(expectedSchema);
        }
    }

    private static void assertSecurity(JsonNode actual, List<String> expectedSchemes) {
        if (expectedSchemes.isEmpty()) {
            assertThat(actual.isArray()).isTrue();
            assertThat(actual).isEmpty();
            return;
        }
        assertThat(actual.isArray()).isTrue();
        assertThat(actual).hasSize(1);
        assertThat(fieldNames(actual.get(0))).containsExactlyElementsOf(new TreeSet<>(expectedSchemes));
    }

    private static void assertWriteOnly(JsonNode schemas, String schemaName, String property) {
        assertThat(schemas.at("/" + schemaName + "/properties/" + property + "/writeOnly").asBoolean()).isTrue();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> arrayValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Endpoint endpoint(
            String path,
            String method,
            String operationId,
            String successStatus,
            List<String> securitySchemes,
            String requestSchema,
            String... errorStatuses) {
        return new Endpoint(
            path,
            method,
            operationId,
            successStatus,
            securitySchemes,
            requestSchema,
            List.of(errorStatuses)
        );
    }

    private static List<String> security(String... schemes) {
        return List.of(schemes);
    }

    private record Endpoint(
        String path,
        String method,
        String operationId,
        String successStatus,
        List<String> securitySchemes,
        String requestSchema,
        List<String> errorStatuses
    ) {
    }
}
