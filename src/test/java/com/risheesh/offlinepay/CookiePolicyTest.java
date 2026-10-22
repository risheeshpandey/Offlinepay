package com.risheesh.offlinepay;

import com.risheesh.offlinepay.auth.AuthCookies;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class CookiePolicyTest {
    @Test void productionIssueAndDeletionHaveSameSecureCookieScope() {
        AuthCookies cookies = new AuthCookies(); ReflectionTestUtils.setField(cookies, "secure", true);
        var issue = new MockHttpServletResponse(); cookies.write(issue, "opaque-demo-value", 600);
        var clear = new MockHttpServletResponse(); cookies.clear(clear);
        for (var response : new MockHttpServletResponse[] {issue, clear}) {
            String header = response.getHeader("Set-Cookie");
            assertTrue(header.contains("Secure")); assertTrue(header.contains("HttpOnly"));
            assertTrue(header.contains("SameSite=Strict")); assertTrue(header.contains("Path=/api/v1/auth"));
            assertFalse(header.contains("Domain=")); assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        assertTrue(clear.getHeader("Set-Cookie").contains("Max-Age=0"));
    }
}
