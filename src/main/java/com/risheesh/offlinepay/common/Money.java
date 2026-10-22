package com.risheesh.offlinepay.common;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/** API amounts are INR; storage uses integer paise, never float or double. */
public final class Money {
    private Money() {}
    public static long toPaise(BigDecimal inr) {
        try {
            long paise = inr.movePointRight(2).longValueExact();
            if (paise <= 0 || paise > 1_000_000_000L) throw new ArithmeticException();
            return paise;
        } catch (ArithmeticException | NullPointerException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Amount must be positive, at most INR 10000000, with no fractional paise");
        }
    }
    public static BigDecimal toInr(long paise) { return BigDecimal.valueOf(paise, 2); }
}
