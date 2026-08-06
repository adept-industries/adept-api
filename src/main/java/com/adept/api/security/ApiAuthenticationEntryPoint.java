package com.adept.api.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemWriter problemWriter;

    public ApiAuthenticationEntryPoint(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        problemWriter.write(request, response, ProblemCode.SESSION_INVALID);
    }
}
