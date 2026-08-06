package com.adept.api.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.stereotype.Component;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemWriter problemWriter;

    public ApiAccessDeniedHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        ProblemCode code = exception instanceof MissingCsrfTokenException
                || exception instanceof InvalidCsrfTokenException
            ? ProblemCode.CSRF_INVALID
            : ProblemCode.WORKSPACE_FORBIDDEN;
        problemWriter.write(request, response, code);
    }
}
