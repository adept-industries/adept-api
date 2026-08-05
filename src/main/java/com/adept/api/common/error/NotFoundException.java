package com.adept.api.common.error;

/**
 * Thrown by services when a requested resource does not exist in the current
 * workspace, or when existence must not be revealed across workspace boundaries.
 *
 * <p>The global exception handler maps this to HTTP 404 with an
 * {@code application/problem+json} body. Cross-workspace lookups should return
 * {@code NotFoundException} rather than {@code ForbiddenException} so that the
 * response does not reveal whether the resource exists in another workspace.
 */
public class NotFoundException extends RuntimeException {

    // Stable machine-readable code exposed in the "code" problem field.
    private final String code;

    /**
     * @param code   a SCREAMING_SNAKE_CASE identifier, e.g. {@code "REPOSITORY_NOT_FOUND"}.
     * @param detail safe, user-facing description included in the problem body.
     */
    public NotFoundException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
