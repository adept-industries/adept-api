package com.adept.api.common.error;

import java.util.Objects;

public class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final String safeDetail;

    public ApiException(ProblemCode code) {
        this(code, code.defaultDetail());
    }

    public ApiException(ProblemCode code, String safeDetail) {
        super(null, null, false, false);
        this.code = Objects.requireNonNull(code, "code");
        this.safeDetail = Objects.requireNonNull(safeDetail, "safeDetail");
    }

    public ProblemCode code() {
        return code;
    }

    public String safeDetail() {
        return safeDetail;
    }
}
