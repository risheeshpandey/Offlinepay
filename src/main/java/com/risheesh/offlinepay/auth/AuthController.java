package com.risheesh.offlinepay.auth;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import com.risheesh.offlinepay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService service;
    private final AuthCookies cookies;
    public record AccessResponse(String accessToken, String tokenType, long expiresIn) {}
    public record CsrfResponse(String headerName, String token) {}
    public record SignupRequest(@NotBlank @Size(max=100) String name,
            @NotNull @Pattern(regexp="[6-9][0-9]{9}") String mobileNumber,
            @NotNull @Size(min=10,max=72) @Pattern(regexp="[\\x20-\\x7E]+") String password) {}
    public record LoginRequest(@NotNull @Pattern(regexp="[6-9][0-9]{9}") String mobileNumber,
            @NotNull @Size(min=1,max=72) @Pattern(regexp="[\\x20-\\x7E]+") String password) {}
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.SignupResult signup(@Valid @RequestBody SignupRequest request) {
        return service.signup(request.name(), request.mobileNumber(), request.password());
    }
    @PostMapping("/login")
    AccessResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return issue(service.login(request.mobileNumber(), request.password()), response);
    }

    @PostMapping("/refresh")
    AccessResponse refresh(@CookieValue(name = AuthCookies.REFRESH, required = false) String token,
                           HttpServletResponse response) {
        try { return issue(service.refresh(token), response); }
        catch (ApiException ex) { cookies.clear(response); throw ex; }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = AuthCookies.REFRESH, required = false) String token,
                HttpServletResponse response) {
        try { if (token != null) service.logout(token); }
        finally { cookies.clear(response); }
    }

    private AccessResponse issue(AuthService.TokenResult result, HttpServletResponse response) {
        cookies.write(response, result.refreshToken(), result.refreshExpiresIn());
        return new AccessResponse(result.accessToken(), result.tokenType(), result.expiresIn());
    }
}
