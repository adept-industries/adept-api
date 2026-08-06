package com.adept.api.common.error;

public final class UnauthorizedException extends ApiException {

    public UnauthorizedException(ProblemCode code) {
        super(code);
    }

    public UnauthorizedException(ProblemCode code, String safeDetail) {
        super(code, safeDetail);
    }
}
