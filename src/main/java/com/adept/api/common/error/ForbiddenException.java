package com.adept.api.common.error;

/**
 * Thrown by services when the authenticated principal does not have permission
 * to perform the requested operation.
 *
 * <p>The global exception handler maps this to HTTP 403 with an
 * {@code application/problem+json} body. Use {@link NotFoundException} instead
 * when the resource must not be revealed to exist (e.g. cross-workspace access).
 */
public class ForbiddenException extends RuntimeException {

    // Stable machine-readable code exposed in the "code" problem field.
    private final String code;

    /**
     * @param code   a SCREAMING_SNAKE_CASE identifier, e.g. {@code "REPOSITORY_FORBIDDEN"}.
     * @param detail safe, user-facing description included in the problem body.
     */
    public ForbiddenException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
