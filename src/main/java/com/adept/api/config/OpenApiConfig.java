package com.adept.api.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

    private static final String CSRF = "/api/v1/auth/csrf";
    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String VERIFY_EMAIL = "/api/v1/auth/verify-email";
    private static final String RESEND_VERIFICATION = "/api/v1/auth/resend-verification";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";
    private static final String SWITCH_WORKSPACE = "/api/v1/auth/switch-workspace/{workspaceId}";
    private static final String FORGOT_PASSWORD = "/api/v1/auth/forgot-password";
    private static final String RESET_PASSWORD = "/api/v1/auth/reset-password";
    private static final String WORKSPACES = "/api/v1/workspaces";
    private static final String CURRENT_WORKSPACE = "/api/v1/workspaces/current";

    private static final Set<String> PHASE_2_PATHS = Set.of(
        CSRF,
        SIGNUP,
        VERIFY_EMAIL,
        RESEND_VERIFICATION,
        LOGIN,
        REFRESH,
        LOGOUT,
        ME,
        SWITCH_WORKSPACE,
        FORGOT_PASSWORD,
        RESET_PASSWORD,
        WORKSPACES,
        CURRENT_WORKSPACE
    );

    @Bean
    public OpenAPI customOpenAPI() {
        Components components = new Components()
            .addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("HTTP Bearer JWT access token."))
            .addSecuritySchemes("refreshCookie", new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("adept_refresh")
                .description("HttpOnly refresh-session cookie. The cookie is required for refresh and workspace switching."))
            .addSecuritySchemes("csrfHeader", new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-XSRF-TOKEN")
                .description("CSRF header whose value must match the paired readable XSRF-TOKEN cookie."));

        addProblemSchemas(components);

        return new OpenAPI()
            .info(new Info()
                .title("Adept API")
                .version("v1")
                .description("Adept Industries Phase 2 API contract."))
            .components(components);
    }

    @Bean
    public GlobalOpenApiCustomizer phase2ContractCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().keySet().removeIf(path -> !PHASE_2_PATHS.contains(path));
            configureSchemas(openApi.getComponents());

            configure(
                openApi,
                CSRF,
                PathItem.HttpMethod.GET,
                "getCsrfToken",
                "Bootstrap CSRF protection",
                "Returns 204 and emits the readable XSRF-TOKEN cookie used with the X-XSRF-TOKEN header.",
                "204",
                "CSRF cookie issued",
                null,
                SecurityProfile.PUBLIC,
                Set.of(),
                CookieBehavior.CSRF
            );
            operation(openApi, CSRF, PathItem.HttpMethod.GET).setParameters(null);

            configureBodyOperation(openApi, SIGNUP, "signup", "Create an account and workspace", "201",
                "Account created", "SignupResponse", SecurityProfile.CSRF,
                Set.of("400", "403", "409", "413", "415", "429"), CookieBehavior.NONE);
            configureBodyOperation(openApi, VERIFY_EMAIL, "verifyEmail", "Verify an email address", "204",
                "Email verified", null, SecurityProfile.CSRF,
                Set.of("400", "403", "413", "415", "429"), CookieBehavior.NONE);
            configureBodyOperation(openApi, RESEND_VERIFICATION, "resendVerification", "Request another verification email", "202",
                "Request accepted", null, SecurityProfile.CSRF,
                Set.of("400", "403", "413", "415", "429"), CookieBehavior.NONE);
            configureBodyOperation(openApi, LOGIN, "login", "Create a browser session", "200",
                "Session created", "AuthSessionResponse", SecurityProfile.CSRF,
                Set.of("400", "401", "403", "413", "415", "429"), CookieBehavior.REFRESH_AND_CSRF);
            configureBodyOperation(openApi, REFRESH, "refreshSession", "Rotate the refresh token and issue session state", "200",
                "Session refreshed", "AuthSessionResponse", SecurityProfile.REFRESH_CSRF,
                Set.of("400", "401", "403", "413", "415", "429"), CookieBehavior.REFRESH);

            configure(
                openApi,
                LOGOUT,
                PathItem.HttpMethod.POST,
                "logoutSession",
                "End the browser session",
                "Idempotently clears session cookies. A valid adept_refresh cookie is revoked when present; a missing cookie still returns 204.",
                "204",
                "Session cookies cleared",
                null,
                SecurityProfile.CSRF,
                Set.of("403", "429"),
                CookieBehavior.REFRESH_AND_CSRF
            );

            configure(
                openApi,
                ME,
                PathItem.HttpMethod.GET,
                "getCurrentUser",
                "Get the current identity context",
                null,
                "200",
                "Current identity returned",
                componentRef("MeResponse"),
                SecurityProfile.BEARER,
                Set.of("401", "403"),
                CookieBehavior.NONE
            );

            configure(
                openApi,
                SWITCH_WORKSPACE,
                PathItem.HttpMethod.POST,
                "switchWorkspace",
                "Switch the active workspace",
                null,
                "200",
                "Workspace selected",
                componentRef("AuthSessionResponse"),
                SecurityProfile.REFRESH_CSRF,
                Set.of("400", "401", "403", "404", "429"),
                CookieBehavior.NONE
            );

            configureBodyOperation(openApi, FORGOT_PASSWORD, "forgotPassword", "Request a password reset", "202",
                "Request accepted", null, SecurityProfile.CSRF,
                Set.of("400", "403", "413", "415", "429"), CookieBehavior.NONE);
            configureBodyOperation(openApi, RESET_PASSWORD, "resetPassword", "Reset a password", "204",
                "Password reset and session cookies cleared", null, SecurityProfile.CSRF,
                Set.of("400", "403", "413", "415", "429"), CookieBehavior.REFRESH_AND_CSRF);

            configure(
                openApi,
                WORKSPACES,
                PathItem.HttpMethod.GET,
                "listWorkspaces",
                "List active workspace memberships",
                null,
                "200",
                "Accessible workspaces returned",
                new ArraySchema().items(componentRef("WorkspaceSummaryResponse")),
                SecurityProfile.BEARER,
                Set.of("401", "403"),
                CookieBehavior.NONE
            );
            configure(
                openApi,
                CURRENT_WORKSPACE,
                PathItem.HttpMethod.GET,
                "getCurrentWorkspace",
                "Get the current workspace",
                null,
                "200",
                "Current workspace returned",
                componentRef("CurrentWorkspaceResponse"),
                SecurityProfile.BEARER,
                Set.of("401", "403"),
                CookieBehavior.NONE
            );
            configure(
                openApi,
                CURRENT_WORKSPACE,
                PathItem.HttpMethod.PATCH,
                "updateCurrentWorkspace",
                "Update current workspace settings",
                null,
                "200",
                "Workspace updated",
                componentRef("CurrentWorkspaceResponse"),
                SecurityProfile.BEARER_CSRF,
                Set.of("400", "401", "403", "409", "413", "415"),
                CookieBehavior.NONE
            );
            configure(
                openApi,
                CURRENT_WORKSPACE,
                PathItem.HttpMethod.DELETE,
                "deleteCurrentWorkspace",
                "Request current workspace deletion",
                "Marks the workspace DELETING, suspends integrations, and queues one pending DELETE_WORKSPACE job. The deletion worker is intentionally deferred beyond Phase 2.",
                "202",
                "Deletion requested",
                componentRef("WorkspaceDeletionResponse"),
                SecurityProfile.BEARER_CSRF,
                Set.of("400", "401", "403", "409", "413", "415", "429"),
                CookieBehavior.NONE
            );
        };
    }

    private static void configureBodyOperation(
            OpenAPI openApi,
            String path,
            String operationId,
            String summary,
            String successCode,
            String successDescription,
            String responseSchema,
            SecurityProfile security,
            Set<String> errors,
            CookieBehavior cookies) {
        configure(
            openApi,
            path,
            PathItem.HttpMethod.POST,
            operationId,
            summary,
            null,
            successCode,
            successDescription,
            responseSchema == null ? null : componentRef(responseSchema),
            security,
            errors,
            cookies
        );
    }

    private static void configure(
            OpenAPI openApi,
            String path,
            PathItem.HttpMethod method,
            String operationId,
            String summary,
            String description,
            String successCode,
            String successDescription,
            Schema<?> successSchema,
            SecurityProfile security,
            Set<String> errorCodes,
            CookieBehavior cookies) {
        Operation operation = operation(openApi, path, method);
        operation.setOperationId(operationId);
        operation.setSummary(summary);
        operation.setDescription(description);
        operation.setSecurity(security.requirements());

        ApiResponses responses = new ApiResponses();
        ApiResponse success = response(successDescription, successSchema);
        addStandardHeaders(success, cookies, false);
        responses.addApiResponse(successCode, success);

        for (String errorCode : ordered(errorCodes)) {
            ApiResponse problem = problemResponse(errorCode);
            addStandardHeaders(
                problem,
                cookies,
                "401".equals(errorCode) && security == SecurityProfile.REFRESH_CSRF
            );
            responses.addApiResponse(errorCode, problem);
        }
        operation.setResponses(responses);
    }

    private static Operation operation(OpenAPI openApi, String path, PathItem.HttpMethod method) {
        PathItem pathItem = openApi.getPaths().get(path);
        if (pathItem == null || pathItem.readOperationsMap().get(method) == null) {
            throw new IllegalStateException("Missing Phase 2 OpenAPI operation " + method + " " + path);
        }
        return pathItem.readOperationsMap().get(method);
    }

    private static ApiResponse response(String description, Schema<?> schema) {
        ApiResponse response = new ApiResponse().description(description);
        if (schema != null) {
            response.setContent(new Content().addMediaType(
                JSON,
                new io.swagger.v3.oas.models.media.MediaType().schema(schema)
            ));
        }
        return response;
    }

    private static ApiResponse problemResponse(String status) {
        ApiResponse response = new ApiResponse()
            .description(problemDescription(status))
            .content(new Content().addMediaType(
                PROBLEM_JSON,
                new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref(PROBLEM_REF))
            ));
        if ("429".equals(status)) {
            response.addHeaderObject("Retry-After", new Header()
                .description("Seconds until the request may be retried.")
                .schema(new IntegerSchema().format("int64").minimum(java.math.BigDecimal.ZERO)));
        }
        return response;
    }

    private static String problemDescription(String status) {
        return switch (status) {
            case "400" -> "Validation failed or the request was malformed";
            case "401" -> "Authentication or session validation failed";
            case "403" -> "CSRF, origin, membership, or role authorization failed";
            case "404" -> "The scoped resource was not found";
            case "409" -> "The requested state conflicts with current state";
            case "413" -> "The request body exceeded the 16 KiB limit";
            case "415" -> "The request body used an unsupported media type";
            case "429" -> "A request rate limit was exceeded";
            default -> throw new IllegalArgumentException("Unsupported problem response " + status);
        };
    }

    private static void addStandardHeaders(ApiResponse response, CookieBehavior cookies, boolean mayClearRefresh) {
        response.addHeaderObject("Cache-Control", new Header()
            .description("Sensitive Phase 2 responses are not cached.")
            .schema(new StringSchema()._enum(List.of("no-store"))));

        if (cookies != CookieBehavior.NONE || mayClearRefresh) {
            response.addHeaderObject("Set-Cookie", new Header()
                .description(cookieDescription(cookies, mayClearRefresh))
                .schema(new StringSchema()));
        }
    }

    private static String cookieDescription(CookieBehavior cookies, boolean mayClearRefresh) {
        if (mayClearRefresh && cookies == CookieBehavior.NONE) {
            return "May clear the adept_refresh cookie after invalid session state.";
        }
        return switch (cookies) {
            case CSRF -> "Sets the readable XSRF-TOKEN cookie.";
            case REFRESH -> "Rotates or clears the HttpOnly adept_refresh cookie.";
            case REFRESH_AND_CSRF -> "Sets or clears adept_refresh and rotates or expires XSRF-TOKEN as described by the operation.";
            case NONE -> "May clear the adept_refresh cookie after invalid session state.";
        };
    }

    private static List<String> ordered(Set<String> statuses) {
        return new LinkedHashSet<>(List.of("400", "401", "403", "404", "409", "413", "415", "429"))
            .stream()
            .filter(statuses::contains)
            .toList();
    }

    private static void addProblemSchemas(Components components) {
        ObjectSchema fieldError = new ObjectSchema();
        fieldError.setAdditionalProperties(false);
        fieldError.addProperty("field", new StringSchema());
        fieldError.addProperty("message", new StringSchema());
        fieldError.setRequired(List.of("field", "message"));

        ObjectSchema problem = new ObjectSchema();
        problem.setDescription("RFC 9457 problem response with stable Adept fields.");
        problem.setAdditionalProperties(false);
        problem.addProperty("type", new StringSchema().format("uri"));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema().format("int32"));
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema().format("uri"));
        problem.addProperty("code", new StringSchema());
        problem.addProperty("traceId", new StringSchema());
        problem.addProperty("fieldErrors", new ArraySchema().items(componentRef("FieldError")));
        problem.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "traceId"));

        components.addSchemas("FieldError", fieldError);
        components.addSchemas("ProblemDetail", problem);
    }

    private static void configureSchemas(Components components) {
        addProblemSchemas(components);
        components.getSchemas().remove("CsrfToken");
        components.getSchemas().remove("Payload");

        hideProperties(components, "UpdateWorkspaceRequest", "namePresent", "timezonePresent");
        markWriteOnly(components, "SignupRequest", "password");
        markWriteOnly(components, "LoginRequest", "password");
        markWriteOnly(components, "ActionTokenRequest", "token");
        markWriteOnly(components, "ResetPasswordRequest", "token", "newPassword");
        markWriteOnly(components, "DeleteWorkspaceRequest", "password");

        require(components, "UserSummary", "id", "email", "displayName", "emailVerified");
        require(components, "MembershipSummary", "id", "workspaceId", "workspaceName", "workspaceSlug", "timezone", "role");
        require(components, "WorkspaceSummaryResponse", "id", "name", "slug", "timezone", "role");
        require(components, "SignupResponse", "user", "workspace", "emailVerificationRequired");
        require(components, "MeResponse", "user", "currentMembership", "workspaces");
        require(components, "CurrentWorkspaceResponse", "id", "name", "slug", "timezone", "role", "membershipId");
        require(components, "WorkspaceDeletionResponse", "workspaceId", "status");

        Schema<?> updateRequest = components.getSchemas().get("UpdateWorkspaceRequest");
        if (updateRequest != null) {
            updateRequest.setDescription("Presence-aware patch. At least one of name or timezone is required; explicit null is invalid.");
            updateRequest.setAdditionalProperties(false);
        }

        ObjectSchema authenticated = sessionBranch(false);
        authenticated.setRequired(List.of(
            "accessToken",
            "expiresInSeconds",
            "workspaceSelectionRequired",
            "user",
            "currentMembership",
            "workspaces"
        ));

        ObjectSchema selectionRequired = sessionBranch(true);
        selectionRequired.getProperties().remove("accessToken");
        selectionRequired.getProperties().remove("expiresInSeconds");
        selectionRequired.getProperties().remove("currentMembership");
        selectionRequired.setRequired(List.of("workspaceSelectionRequired", "user", "workspaces"));

        components.addSchemas("AuthenticatedSessionResponse", authenticated);
        components.addSchemas("WorkspaceSelectionSessionResponse", selectionRequired);
        components.addSchemas("AuthSessionResponse", new ComposedSchema().oneOf(List.of(
            componentRef("AuthenticatedSessionResponse"),
            componentRef("WorkspaceSelectionSessionResponse")
        )));
    }

    private static ObjectSchema sessionBranch(boolean selectionRequired) {
        BooleanSchema selection = new BooleanSchema();
        selection.setEnum(List.of(selectionRequired));

        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(false);
        schema.addProperty("accessToken", new StringSchema().description("Memory-only Bearer JWT."));
        schema.addProperty("expiresInSeconds", new IntegerSchema().format("int32").minimum(java.math.BigDecimal.ONE));
        schema.addProperty("workspaceSelectionRequired", selection);
        schema.addProperty("user", componentRef("UserSummary"));
        schema.addProperty("currentMembership", componentRef("MembershipSummary"));
        schema.addProperty("workspaces", new ArraySchema().items(componentRef("WorkspaceSummaryResponse")));
        return schema;
    }

    private static void hideProperties(Components components, String schemaName, String... properties) {
        Schema<?> schema = components.getSchemas().get(schemaName);
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        for (String property : properties) {
            schema.getProperties().remove(property);
        }
    }

    private static void markWriteOnly(Components components, String schemaName, String... properties) {
        Schema<?> schema = components.getSchemas().get(schemaName);
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        for (String property : properties) {
            Schema<?> propertySchema = schema.getProperties().get(property);
            if (propertySchema != null) {
                propertySchema.setWriteOnly(true);
            }
        }
    }

    private static void require(Components components, String schemaName, String... properties) {
        Schema<?> schema = components.getSchemas().get(schemaName);
        if (schema != null) {
            schema.setRequired(List.of(properties));
            schema.setAdditionalProperties(false);
        }
    }

    private static Schema<?> componentRef(String schemaName) {
        return new Schema<>().$ref("#/components/schemas/" + schemaName);
    }

    private enum SecurityProfile {
        PUBLIC,
        CSRF,
        BEARER,
        BEARER_CSRF,
        REFRESH_CSRF;

        List<SecurityRequirement> requirements() {
            return switch (this) {
                case PUBLIC -> List.of();
                case CSRF -> List.of(new SecurityRequirement().addList("csrfHeader"));
                case BEARER -> List.of(new SecurityRequirement().addList("bearerAuth"));
                case BEARER_CSRF -> List.of(
                    new SecurityRequirement().addList("bearerAuth").addList("csrfHeader")
                );
                case REFRESH_CSRF -> List.of(
                    new SecurityRequirement().addList("refreshCookie").addList("csrfHeader")
                );
            };
        }
    }

    private enum CookieBehavior {
        NONE,
        CSRF,
        REFRESH,
        REFRESH_AND_CSRF
    }
}
