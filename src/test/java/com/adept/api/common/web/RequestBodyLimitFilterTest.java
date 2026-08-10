package com.adept.api.common.web;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.adept.api.common.error.ProblemResponseFactory;
import com.adept.api.common.error.ProblemWriter;

import static org.assertj.core.api.Assertions.assertThat;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(
        new ProblemWriter(new JsonMapper(), new ProblemResponseFactory()));

    @Test
    void countingStreamStopsChunkedBodyBeforeDownstreamWork() throws Exception {
        MockHttpServletRequest request = unknownLengthRequest(
            "x".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamWork = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            wrappedRequest.getInputStream().readAllBytes();
            downstreamWork.set(true);
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(downstreamWork).isFalse();

        MockHttpServletRequest boundaryRequest = unknownLengthRequest(
            "x".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES));
        AtomicBoolean boundaryDownstreamWork = new AtomicBoolean();

        filter.doFilter(boundaryRequest, new MockHttpServletResponse(), (wrappedRequest, wrappedResponse) -> {
            assertThat(wrappedRequest.getInputStream().readAllBytes())
                .hasSize(RequestBodyLimitFilter.MAX_BODY_BYTES);
            boundaryDownstreamWork.set(true);
        });

        assertThat(boundaryDownstreamWork).isTrue();
    }

    private static MockHttpServletRequest unknownLengthRequest(String body) {
        return new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            {
                setRequestURI("/api/v1/auth/login");
                setContent(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }
}
