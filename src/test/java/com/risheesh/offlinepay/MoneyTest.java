package com.risheesh.offlinepay;

import com.risheesh.offlinepay.common.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void storesExactPaise() {
        assertEquals(101, Money.toPaise(new BigDecimal("1.01")));
        assertEquals(100, Money.toPaise(new BigDecimal("1.000")));
        assertEquals(new BigDecimal("0.01"), Money.toInr(1));
    }
    @Test void rejectsInvalidMoney() {
        for (String amount : new String[]{"0", "-1", "0.001", "10000000.01", "99999999999999999999999"})
            assertThrows(ApiException.class, () -> Money.toPaise(new BigDecimal(amount)), amount);
    }
}
