package com.adept.api.common.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.adept.api.common.error.PayloadTooLargeException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

public final class RequestBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 16 * 1024;

    private static final Set<String> UNSAFE_METHODS = Set.of(
        HttpMethod.POST.name(),
        HttpMethod.PUT.name(),
        HttpMethod.PATCH.name(),
        HttpMethod.DELETE.name()
    );

    private final ProblemWriter problemWriter;

    public RequestBodyLimitFilter(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/workspaces"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            problemWriter.write(request, response, ProblemCode.PAYLOAD_TOO_LARGE);
            return;
        }

        try {
            filterChain.doFilter(new CountingRequestWrapper(request), response);
        } catch (PayloadTooLargeException exception) {
            if (!response.isCommitted()) {
                problemWriter.write(request, response, ProblemCode.PAYLOAD_TOO_LARGE);
                return;
            }
            throw exception;
        }
    }

    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {

        private CountingServletInputStream inputStream;

        private CountingRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new CountingServletInputStream(super.getInputStream());
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private int count;

        private CountingServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                addCount(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int remainingWithSentinel = MAX_BODY_BYTES + 1 - count;
            int read = delegate.read(buffer, offset, Math.min(length, Math.max(1, remainingWithSentinel)));
            if (read > 0) {
                addCount(read);
            }
            return read;
        }

        private void addCount(int amount) {
            count += amount;
            if (count > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
