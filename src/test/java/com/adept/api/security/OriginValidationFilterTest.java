package com.adept.api.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;
import com.adept.api.support.TestAppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import tools.jackson.databind.json.JsonMapper;

class OriginValidationFilterTest {

    @Test
    void permitsAbsentAndEquivalentOrigin() throws Exception {
        ProblemWriter writer = problemWriter();
        OriginValidationFilter filter = new OriginValidationFilter(TestAppProperties.create(), writer);

        MockHttpServletRequest absent = request();
        MockFilterChain absentChain = new MockFilterChain();
        filter.doFilter(absent, new MockHttpServletResponse(), absentChain);
        assertThat(absentChain.getRequest()).isNotNull();

        MockHttpServletRequest equivalent = request();
        equivalent.addHeader(HttpHeaders.ORIGIN, "HTTP://LOCALHOST:3000");
        MockFilterChain equivalentChain = new MockFilterChain();
        filter.doFilter(equivalent, new MockHttpServletResponse(), equivalentChain);
        assertThat(equivalentChain.getRequest()).isNotNull();
        assertThat(equivalentChain.getResponse().isCommitted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://localhost:3000",
        "http://example.com",
        "null",
        "http://user@localhost:3000",
        "http://localhost:3000/path",
        "http://localhost:3000?q=1"
    })
    void rejectsMalformedOrMismatchedOrigins(String origin) throws Exception {
        ProblemWriter writer = problemWriter();
        OriginValidationFilter filter = new OriginValidationFilter(TestAppProperties.create(), writer);
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.ORIGIN, origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(ProblemCode.ORIGIN_INVALID.status().value());
        assertThat(response.getContentAsString()).contains("ORIGIN_INVALID");
    }

    @Test
    void rejectsMultipleOriginHeaders() throws Exception {
        ProblemWriter writer = problemWriter();
        OriginValidationFilter filter = new OriginValidationFilter(TestAppProperties.create(), writer);
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        request.addHeader(HttpHeaders.ORIGIN, "http://example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_INVALID");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRequestURI("/api/v1/auth/login");
        return request;
    }

    private static ProblemWriter problemWriter() {
        return new ProblemWriter(new JsonMapper(), new com.adept.api.common.error.ProblemResponseFactory());
    }
}
