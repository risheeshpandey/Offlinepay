package com.risheesh.offlinepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.security.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestRateLimitFilterTest {
    RedisRequestLimiter limiter = mock(RedisRequestLimiter.class);
    RequestRateLimitFilter filter = new RequestRateLimitFilter(limiter, new ObjectMapper().findAndRegisterModules(), 30, 120);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST",path); request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1"); return request;
    }
    @Test void rateExceededReturns429AndDoesNotCallApplication() throws Exception {
        when(limiter.allow(anyString(), anyInt())).thenReturn(false);
        var chain = new MockFilterChain(); var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/auth/login"), response, chain);
        assertEquals(429, response.getStatus()); assertEquals("60",response.getHeader("Retry-After"));
        assertNull(chain.getRequest()); assertTrue(response.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
    }
    @Test void redisFailureIs503AndMoneyRequestIsNotExecuted() throws Exception {
        when(limiter.allow(anyString(), anyInt())).thenThrow(new IllegalStateException());
        var chain = new MockFilterChain(); var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/offline-sync/payments"), response, chain);
        assertEquals(503, response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void validatedJwtSubjectIsUsedAndForwardedHeaderIsNotTrusted() throws Exception {
        when(limiter.allow(anyString(), anyInt())).thenReturn(true);
        var token = Jwt.withTokenValue("fixture").header("alg","HS256").subject("user-one").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
        var request = request("/api/v1/notifications"); request.addHeader("X-Forwarded-For","attacker-controlled");
        var chain = new MockFilterChain(); filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(limiter).allow("api:user:user-one",120); assertNotNull(chain.getRequest());
    }
    @Test void healthBypassesRedis() throws Exception {
        var chain = new MockFilterChain(); filter.doFilter(request("/actuator/health"),new MockHttpServletResponse(),chain);
        verifyNoInteractions(limiter); assertNotNull(chain.getRequest());
    }
}
