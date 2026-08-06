package com.adept.api.common.error;

public final class ForbiddenException extends ApiException {

    public ForbiddenException(ProblemCode code) {
        super(code);
    }

    public ForbiddenException(ProblemCode code, String safeDetail) {
        super(code, safeDetail);
    }
}
