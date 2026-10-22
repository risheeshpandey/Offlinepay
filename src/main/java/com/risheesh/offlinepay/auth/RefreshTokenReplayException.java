package com.risheesh.offlinepay.auth;

import com.risheesh.offlinepay.common.ApiException;
import org.springframework.http.HttpStatus;

public class RefreshTokenReplayException extends ApiException {
    public RefreshTokenReplayException() {
        super(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "Refresh token was reused; log in again");
    }
}
