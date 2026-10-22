package com.risheesh.offlinepay.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String REFRESH = "offlinepay_rt";
    public static final String PATH = "/api/v1/auth";
    @Value("${offlinepay.cookies-secure}")
    private boolean secure;

    public void write(HttpServletResponse response, String token, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH, token)
                .httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(seconds).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    public void clear(HttpServletResponse response) { write(response, "", 0); }
}
