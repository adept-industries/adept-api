package com.adept.api.common.error;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.adept.api.common.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@Component
public final class ProblemResponseFactory {

    public ProblemDetail create(ProblemCode code, HttpServletRequest request) {
        return create(code, code.defaultDetail(), request, List.of());
    }

    public ProblemDetail create(ProblemCode code, String safeDetail, HttpServletRequest request) {
        return create(code, safeDetail, request, List.of());
    }

    public ProblemDetail create(
            ProblemCode code,
            String safeDetail,
            HttpServletRequest request,
            List<FieldViolation> fieldViolations) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setDetail(safeDetail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceId(request));
        if (!fieldViolations.isEmpty()) {
            problem.setProperty("fieldErrors", List.copyOf(fieldViolations));
        }
        return problem;
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, generated);
        return generated;
    }
}
