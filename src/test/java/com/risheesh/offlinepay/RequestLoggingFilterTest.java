package com.risheesh.offlinepay;

import com.risheesh.offlinepay.common.RequestLoggingFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingFilterTest {
    @Test void unexpectedExceptionLogsFailureStatusAndClearsThreadContext() {
        var filter = new RequestLoggingFilter();
        var request = new MockHttpServletRequest("GET", "/test");
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class,
                () -> filter.doFilter(request, response, (req, res) -> { throw new ServletException("test failure"); }));
        assertEquals(500, response.getStatus());
        assertNotNull(response.getHeader("X-Request-ID"));
        assertNull(MDC.get("requestId"));
    }

    @Test void invalidCorrelationHeaderIsReplacedWithSafeId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/test");
        request.addHeader("X-Request-ID", "bad\nheader");
        var response = new MockHttpServletResponse();
        new RequestLoggingFilter().doFilter(request, response, (req, res) -> {});
        assertDoesNotThrow(() -> java.util.UUID.fromString(response.getHeader("X-Request-ID")));
        assertNull(MDC.get("requestId"));
    }
}
