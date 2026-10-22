package com.risheesh.offlinepay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.ApiExceptionHandler.ErrorResponse;
import com.risheesh.offlinepay.common.Idempotency;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

// Installed only in the security chain, after Bearer validation. No servlet auto-registration.
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private final RedisRequestLimiter limiter;
    private final ObjectMapper json;
    private final int authLimit;
    private final int apiLimit;
    public RequestRateLimitFilter(RedisRequestLimiter limiter, ObjectMapper json, int authLimit, int apiLimit) {
        if (authLimit < 1 || apiLimit < 1) throw new IllegalArgumentException("Rate limits must be positive");
        this.limiter = limiter; this.json = json; this.authLimit = authLimit; this.apiLimit = apiLimit;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/v1/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean auth = request.getServletPath().startsWith("/api/v1/auth/");
        var principal = SecurityContextHolder.getContext().getAuthentication();
        String identity = !auth && principal instanceof JwtAuthenticationToken jwt
                ? "user:" + jwt.getToken().getSubject()
                : "ip:" + Idempotency.fingerprint(request.getRemoteAddr());
        boolean allowed;
        try { allowed = limiter.allow((auth ? "auth:" : "api:") + identity, auth ? authLimit : apiLimit); }
        catch (RuntimeException ex) {
            // Explicit fail-closed policy. Never execute a money request without this protection.
            reject(response, 503, "RATE_LIMIT_UNAVAILABLE", "Request protection is temporarily unavailable"); return;
        }
        if (!allowed) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "RATE_LIMIT_EXCEEDED", "Too many requests; retry later"); return;
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), new ErrorResponse(code, message, Instant.now()));
    }
}
