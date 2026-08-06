package com.adept.api.common.error;

public final class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException() {
        super(ProblemCode.PAYLOAD_TOO_LARGE);
    }
}
