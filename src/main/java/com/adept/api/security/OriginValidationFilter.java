package com.adept.api.security;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;
import com.adept.api.config.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class OriginValidationFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of(
        HttpMethod.POST.name(),
        HttpMethod.PUT.name(),
        HttpMethod.PATCH.name(),
        HttpMethod.DELETE.name()
    );

    private final Set<OriginTuple> allowedOrigins;
    private final ProblemWriter problemWriter;

    public OriginValidationFilter(AppProperties properties, ProblemWriter problemWriter) {
        Set<OriginTuple> set = new HashSet<>();
        if (properties.frontendBaseUrl() != null) {
            set.add(OriginTuple.from(properties.frontendBaseUrl(), true));
        }
        set.add(new OriginTuple("http", "localhost", 5173));
        set.add(new OriginTuple("http", "127.0.0.1", 5173));
        set.add(new OriginTuple("http", "localhost", 3000));
        set.add(new OriginTuple("http", "127.0.0.1", 3000));
        this.allowedOrigins = Collections.unmodifiableSet(set);
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !UNSAFE_METHODS.contains(request.getMethod())
            || !uri.startsWith("/api/v1/")
            || uri.startsWith("/api/v1/webhooks");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (values.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (values.size() != 1 || values.getFirst().contains(",")) {
            problemWriter.write(request, response, ProblemCode.ORIGIN_INVALID);
            return;
        }

        OriginTuple submitted;
        try {
            submitted = OriginTuple.from(URI.create(values.getFirst()), false);
        } catch (IllegalArgumentException exception) {
            problemWriter.write(request, response, ProblemCode.ORIGIN_INVALID);
            return;
        }

        if (!allowedOrigins.contains(submitted)) {
            problemWriter.write(request, response, ProblemCode.ORIGIN_INVALID);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record OriginTuple(String scheme, String host, int effectivePort) {

        private static OriginTuple from(URI value, boolean allowCanonicalRootPath) {
            String scheme = value.getScheme();
            String host = value.getHost();
            String path = value.getRawPath();
            if (scheme == null
                    || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || value.getRawUserInfo() != null
                    || value.getRawQuery() != null
                    || value.getRawFragment() != null
                    || !(path == null || path.isEmpty()
                        || (allowCanonicalRootPath && path.equals("/")))) {
                throw new IllegalArgumentException("Not a browser origin");
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            int port = value.getPort();
            if (port == -1) {
                port = normalizedScheme.equals("https") ? 443 : 80;
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Invalid origin port");
            }
            return new OriginTuple(normalizedScheme, host.toLowerCase(Locale.ROOT), port);
        }
    }
}
