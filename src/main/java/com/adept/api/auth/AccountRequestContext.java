package com.adept.api.auth;

import com.adept.api.common.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletRequest;

record AccountRequestContext(
    String ipAddress,
    String userAgent,
    String traceId
) {
    static AccountRequestContext from(HttpServletRequest request) {
        Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String traceId = trace == null ? "" : trace.toString();
        return new AccountRequestContext(
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            traceId
        );
    }
}
