package com.risheesh.notifications;

import feign.FeignException;
import java.util.Map;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RemoteSecurity {
    @Bean
    OpaqueTokenIntrospector introspector(CoreClient core) {
        return token -> {
            try {
                var session = core.session("Bearer " + token);
                if (session == null || session.userId() == null) throw new BadOpaqueTokenException("Invalid access token");
                String userId = session.userId().toString();
                return new DefaultOAuth2AuthenticatedPrincipal(userId, Map.of("sub", userId), List.of());
            } catch (FeignException ex) {
                if (ex.status() == 429) throw new OAuth2AuthenticationException(new OAuth2Error("rate_limit_exceeded"));
                if (ex.status() == 401 || ex.status() == 403) throw new BadOpaqueTokenException("Invalid access token");
                throw new OAuth2AuthenticationException(new OAuth2Error("identity_unavailable"));
            }
        };
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        return http.csrf(csrf -> csrf.disable()) // Only explicit Bearer credentials; no auth cookies accepted here.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.opaqueToken(t -> t.introspector(introspector))
                        .authenticationEntryPoint((request, response, error) -> {
                            String code = error instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "unauthorized";
                            boolean limited = "rate_limit_exceeded".equals(code);
                            boolean unavailable = "identity_unavailable".equals(code);
                            response.setStatus(limited ? 429 : unavailable ? 503 : 401);
                            if (limited) response.setHeader("Retry-After", "60");
                            response.setContentType("application/json");
                            response.getWriter().write("{\"code\":\"" + (limited ? "RATE_LIMIT_EXCEEDED" : unavailable ? "IDENTITY_UNAVAILABLE" : "UNAUTHORIZED") + "\"}");
                        }))
                .build();
    }
}
