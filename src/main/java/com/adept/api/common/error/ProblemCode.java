package com.adept.api.common.error;

import org.springframework.http.HttpStatus;

public enum ProblemCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request", "The request could not be parsed."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large", "The request body exceeds the 16 KiB limit."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "The request body must use a supported media type."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Sign in failed", "The email or password is incorrect."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email verification required", "Verify the email address before signing in."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already registered", "An account with that email address already exists."),
    GOOGLE_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "Google sign in failed", "Google authentication could not be completed."),
    GOOGLE_ACCOUNT_CONFLICT(HttpStatus.CONFLICT, "Google account conflict", "That Google account or email is already associated with an account."),
    GOOGLE_SIGNUP_SESSION_INVALID(HttpStatus.UNAUTHORIZED, "Google signup expired", "Restart Google sign in to continue."),
    ACTION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Invalid action token", "The action token is invalid or expired."),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED, "Session invalid", "The session is missing, invalid, expired, or revoked."),
    REFRESH_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "Session revoked", "The refresh session is no longer valid."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF validation failed", "The CSRF token is missing or invalid."),
    ORIGIN_INVALID(HttpStatus.FORBIDDEN, "Origin rejected", "The request origin is not allowed."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Too many requests were received. Try again later."),
    NO_ACTIVE_MEMBERSHIP(HttpStatus.FORBIDDEN, "No active membership", "No active workspace membership is available."),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Workspace not found", "The workspace was not found."),
    WORKSPACE_FORBIDDEN(HttpStatus.FORBIDDEN, "Workspace access denied", "The current identity cannot access this workspace."),
    MANAGER_REQUIRED(HttpStatus.FORBIDDEN, "Manager role required", "A Manager membership is required for this operation."),
    WORKSPACE_CONFLICT(HttpStatus.CONFLICT, "Workspace conflict", "The workspace could not be changed because its state has changed."),
    WORKSPACE_DELETION_ALREADY_REQUESTED(HttpStatus.CONFLICT, "Workspace deletion already requested", "Workspace deletion has already been requested."),
    REAUTHENTICATION_FAILED(HttpStatus.FORBIDDEN, "Reauthentication failed", "Identity verification failed."),
    REAUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN, "Reauthentication required", "Verify your identity again before continuing."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "An unexpected error occurred."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "Endpoint not found", "The requested endpoint does not exist."),
    REPOSITORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Repository not found", "The repository was not found or is not accessible."),
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Project not found", "The project was not found or is not accessible."),
    PROJECT_CONFLICT(HttpStatus.CONFLICT, "Project conflict", "A project with that name already exists in this workspace."),
    INTEGRATION_DISABLED(HttpStatus.BAD_REQUEST, "Integration disabled", "The requested integration is disabled in configuration."),
    INTEGRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Integration not found", "The requested integration was not found."),
    INTEGRATION_CONFLICT(HttpStatus.CONFLICT, "Integration conflict", "This integration is already connected to a workspace."),
    INTEGRATION_STATE_INVALID(HttpStatus.BAD_REQUEST, "Invalid integration state", "The integration authorization state is invalid, expired, or already used."),
    INTEGRATION_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "Provider error", "Failed to communicate with the upstream provider."),
    JIRA_PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Jira project not found", "The requested Jira project was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", "The HTTP method is not supported for this endpoint.");

    private static final String TYPE_BASE = "https://adept.local/problems/";

    private final HttpStatus status;
    private final String title;
    private final String defaultDetail;

    ProblemCode(HttpStatus status, String title, String defaultDetail) {
        this.status = status;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String defaultDetail() {
        return defaultDetail;
    }

    public String type() {
        return TYPE_BASE + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
