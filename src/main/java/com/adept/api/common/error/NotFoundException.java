package com.adept.api.common.error;

public final class NotFoundException extends ApiException {

    public NotFoundException(ProblemCode code) {
        super(code);
    }

    public NotFoundException(ProblemCode code, String safeDetail) {
        super(code, safeDetail);
    }
}
