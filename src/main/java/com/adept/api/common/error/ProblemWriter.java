package com.adept.api.common.error;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.adept.api.common.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ProblemWriter {

    private final ObjectMapper objectMapper;
    private final ProblemResponseFactory factory;

    public ProblemWriter(ObjectMapper objectMapper, ProblemResponseFactory factory) {
        this.objectMapper = objectMapper;
        this.factory = factory;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code)
            throws IOException {
        write(request, response, factory.create(code, request));
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ProblemCode code,
            String safeDetail) throws IOException {
        write(request, response, factory.create(code, safeDetail, request));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        response.resetBuffer();
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Object traceId = problem.getProperties() == null ? null : problem.getProperties().get("traceId");
        if (traceId != null) {
            response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId.toString());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
