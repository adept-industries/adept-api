package com.adept.api.config;

import java.util.List;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        Components components = new Components();

        SecurityScheme bearerAuth = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("HTTP Bearer JWT Access Token");

        SecurityScheme refreshCookie = new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY)
            .in(SecurityScheme.In.COOKIE)
            .name("adept_refresh")
            .description("Refresh session token in HttpOnly cookie");

        SecurityScheme csrfHeader = new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY)
            .in(SecurityScheme.In.HEADER)
            .name("X-XSRF-TOKEN")
            .description("CSRF token in HTTP header");

        components.addSecuritySchemes("bearerAuth", bearerAuth);
        components.addSecuritySchemes("refreshCookie", refreshCookie);
        components.addSecuritySchemes("csrfHeader", csrfHeader);

        Schema<?> fieldErrorSchema = new ObjectSchema()
            .addProperty("field", new StringSchema().example("name"))
            .addProperty("message", new StringSchema().example("Workspace name cannot be null."));

        Schema<?> problemDetailSchema = new ObjectSchema()
            .description("RFC7807 Problem Detail response")
            .addProperty("type", new StringSchema().example("https://adept.local/problems/validation-failed"))
            .addProperty("title", new StringSchema().example("Validation failed"))
            .addProperty("status", new Schema<Integer>().type("integer").example(400))
            .addProperty("detail", new StringSchema().example("One or more fields are invalid."))
            .addProperty("instance", new StringSchema().example("/api/v1/workspaces/current"))
            .addProperty("code", new StringSchema().example("VALIDATION_FAILED"))
            .addProperty("traceId", new StringSchema().example("trace-12345"))
            .addProperty("fieldErrors", new ArraySchema().items(fieldErrorSchema));

        components.addSchemas("ProblemDetail", problemDetailSchema);

        return new OpenAPI()
            .info(new Info()
                .title("Adept API")
                .version("v1")
                .description("Adept Industries Phase 2 OpenAPI Specification"))
            .components(components);
    }

    @Bean
    public GlobalOpenApiCustomizer openApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            Header setCookieHeader = new Header()
                .description("Sets session HTTP cookies (adept_refresh, XSRF-TOKEN)")
                .schema(new StringSchema().example("XSRF-TOKEN=...; Path=/; Secure; SameSite=Strict"));

            Header cacheControlHeader = new Header()
                .description("Disables client caching for sensitive auth endpoints")
                .schema(new StringSchema().example("no-store"));

            openApi.getPaths().forEach((path, pathItem) -> {
                customizePathOperations(path, pathItem, setCookieHeader, cacheControlHeader);
            });
        };
    }

    private void customizePathOperations(String path, PathItem pathItem, Header setCookieHeader, Header cacheControlHeader) {
        if ("/api/v1/auth/csrf".equals(path) && pathItem.getGet() != null) {
            Operation op = pathItem.getGet();
            op.setOperationId("getCsrfToken");
            op.setSummary("Get CSRF Token");
            op.setSecurity(List.of());
            addHeaderToResponses(op, "Set-Cookie", setCookieHeader);
        }

        if ("/api/v1/auth/signup".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("signup");
            op.setSummary("Account Signup");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/verify-email".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("verifyEmail");
            op.setSummary("Verify Email Address");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/resend-verification".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("resendVerification");
            op.setSummary("Resend Email Verification");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/login".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("login");
            op.setSummary("Account Login");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Set-Cookie", setCookieHeader);
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/forgot-password".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("forgotPassword");
            op.setSummary("Request Password Reset");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/reset-password".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("resetPassword");
            op.setSummary("Complete Password Reset");
            op.setSecurity(List.of(new SecurityRequirement().addList("csrfHeader")));
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/me".equals(path) && pathItem.getGet() != null) {
            Operation op = pathItem.getGet();
            op.setOperationId("getCurrentUser");
            op.setSummary("Get Current Identity Context");
            op.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth")));
        }

        if ("/api/v1/auth/refresh".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("refreshSession");
            op.setSummary("Refresh Access Token");
            op.setSecurity(List.of(new SecurityRequirement().addList("refreshCookie").addList("csrfHeader")));
            addHeaderToResponses(op, "Set-Cookie", setCookieHeader);
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/auth/logout".equals(path) && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("logoutSession");
            op.setSummary("Revoke Current Refresh Session");
            op.setSecurity(List.of(new SecurityRequirement().addList("refreshCookie").addList("csrfHeader")));
            addHeaderToResponses(op, "Set-Cookie", setCookieHeader);
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if (path.startsWith("/api/v1/auth/switch-workspace/") && pathItem.getPost() != null) {
            Operation op = pathItem.getPost();
            op.setOperationId("switchWorkspace");
            op.setSummary("Switch Active Workspace");
            op.setSecurity(List.of(new SecurityRequirement().addList("refreshCookie").addList("csrfHeader")));
            addHeaderToResponses(op, "Set-Cookie", setCookieHeader);
            addHeaderToResponses(op, "Cache-Control", cacheControlHeader);
        }

        if ("/api/v1/workspaces".equals(path) && pathItem.getGet() != null) {
            Operation op = pathItem.getGet();
            op.setOperationId("listWorkspaces");
            op.setSummary("List Accessible Workspaces");
            op.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth")));
        }

        if ("/api/v1/workspaces/current".equals(path)) {
            if (pathItem.getGet() != null) {
                Operation op = pathItem.getGet();
                op.setOperationId("getCurrentWorkspace");
                op.setSummary("Get Current Workspace Details");
                op.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth")));
            }
            if (pathItem.getPatch() != null) {
                Operation op = pathItem.getPatch();
                op.setOperationId("updateCurrentWorkspace");
                op.setSummary("Update Current Workspace Settings");
                op.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth").addList("csrfHeader")));
            }
            if (pathItem.getDelete() != null) {
                Operation op = pathItem.getDelete();
                op.setOperationId("deleteCurrentWorkspace");
                op.setSummary("Request Workspace Deletion");
                op.setSecurity(List.of(new SecurityRequirement().addList("bearerAuth").addList("csrfHeader")));
            }
        }
    }

    private void addHeaderToResponses(Operation operation, String headerName, Header header) {
        if (operation != null && operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                response.addHeaderObject(headerName, header);
            }
        }
    }
}
