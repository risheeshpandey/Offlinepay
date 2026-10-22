package com.risheesh.notifications;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;

@Configuration
public class FeignHeaders {
    @Bean RequestInterceptor requestId() {
        return template -> {
            String requestId = MDC.get("requestId");
            if (requestId != null) template.header("X-Request-ID", requestId);
        };
    }
}
