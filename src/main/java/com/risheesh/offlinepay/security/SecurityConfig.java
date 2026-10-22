package com.risheesh.offlinepay.security;

import java.util.Base64;
import com.risheesh.offlinepay.auth.AuthCookies;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecretKey jwtKey(@Value("${offlinepay.jwt-secret-base64}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT secret must contain at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean
    JwtDecoder jwtDecoder(SecretKey key, @Value("${offlinepay.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper json,
            @Value("${offlinepay.cookies-secure}") boolean secure,
            org.springframework.beans.factory.ObjectProvider<RedisRequestLimiter> limiters,
            @Value("${offlinepay.rate-limit.auth-per-minute:30}") int authLimit,
            @Value("${offlinepay.rate-limit.api-per-minute:120}") int apiLimit) throws Exception {
        CookieCsrfTokenRepository csrfTokens = new CookieCsrfTokenRepository();
        csrfTokens.setCookieCustomizer(cookie -> cookie.path(AuthCookies.PATH).httpOnly(true)
                .sameSite("Strict").secure(secure));
        RedisRequestLimiter limiter = limiters.getIfAvailable();
        if (limiter != null) {
            http.addFilterAfter(new RequestRateLimitFilter(limiter, json, authLimit, apiLimit),
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        }
        return http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                    // Only auth endpoints use ambient cookie credentials. Other APIs require a Bearer JWT.
                    .requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                            && request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/auth/")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout", "/api/v1/auth/csrf", "/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/v1/events/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    json.writeValue(response.getOutputStream(), new com.risheesh.offlinepay.common.ApiExceptionHandler.ErrorResponse(
                            "ACCESS_DENIED", "Check CSRF cookie and X-XSRF-TOKEN header", java.time.Instant.now()));
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }
}
