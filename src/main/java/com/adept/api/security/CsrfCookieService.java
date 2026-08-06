package com.adept.api.security;

import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public final class CsrfCookieService {

    private final CsrfTokenRepository csrfTokenRepository;

    public CsrfCookieService(CsrfTokenRepository csrfTokenRepository) {
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public void expire(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
    }
}
