package com.risheesh.offlinepay;

import com.risheesh.offlinepay.auth.AuthService;
import com.risheesh.offlinepay.wallet.WalletService;
import com.risheesh.offlinepay.messaging.OutboxRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = {"offlinepay.notifications.local-enabled=false", "offlinepay.internal-token=local-test-service-token-minimum-32-characters",
    "spring.datasource.url=jdbc:h2:mem:m5core;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class Milestone5CoreTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    @Autowired OutboxRepository outbox;
    @Test void internalSourceRejectsUserBearerAndMissingServiceCredential() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/internal/v1/events/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/events/"+id).with(jwt())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/events/"+id).header("X-Service-Token", "wrong"))
            .andExpect(status().isUnauthorized());
    }
    @Test void sourceDigestMatchesCommittedOutboxAndNeverExposesPayload() throws Exception {
        var user = auth.signup("Split tester", "8550000001", "DemoPassword123!");
        var payment = wallets.demoTopup(user.userId(), new BigDecimal("1.00"), UUID.randomUUID().toString());
        String hash = outbox.findById(payment.transactionId()).orElseThrow().getPayloadHash();
        mvc.perform(get("/internal/v1/events/"+payment.transactionId())
                .header("X-Service-Token", "local-test-service-token-minimum-32-characters"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.payloadHash").value(hash))
            .andExpect(jsonPath("$.payload").doesNotExist());
    }
    @Test void sessionRequiresValidBearerAndLocalInboxIsDisabled() throws Exception {
        mvc.perform(get("/api/v1/internal/session")).andExpect(status().isUnauthorized());
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/v1/internal/session").with(jwt().jwt(j -> j.subject(id.toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(id.toString()));
        mvc.perform(get("/api/v1/notifications").with(jwt())).andExpect(status().isNotFound());
    }
}
