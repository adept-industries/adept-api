package com.adept.api.common.error;

public final class ConflictException extends ApiException {

    public ConflictException(ProblemCode code) {
        super(code);
    }

    public ConflictException(ProblemCode code, String safeDetail) {
        super(code, safeDetail);
    }
}
