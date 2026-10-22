package com.risheesh.offlinepay;

import com.risheesh.offlinepay.auth.AuthService;
import com.risheesh.offlinepay.messaging.*;
import com.risheesh.offlinepay.notification.*;
import com.risheesh.offlinepay.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:kafka4;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "offlinepay.messaging.enabled=true"
})
@ActiveProfiles("test") @DirtiesContext
@EmbeddedKafka(partitions = 3, topics = {"offlinepay.payment-committed.v1","offlinepay.payment-committed.v1.DLT"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers", kraft = true)
class KafkaNotificationTest {
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxTransactions transactions;
    @Autowired PaymentPublisher publisher;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired NotificationService notices;
    @Autowired JdbcTemplate jdbc;
    @Test void actualBrokerDeliversDuplicatesOnceAndArchivesPoisonMessage() throws Exception {
        var user = auth.signup("Kafka learner","8111111111","DemoPassword123!");
        var payment = wallets.demoTopup(user.userId(),new BigDecimal("20"), UUID.randomUUID().toString());
        var claim = transactions.claim(payment.transactionId()).orElseThrow();
        publisher.publish(claim); transactions.finish(claim,null);
        kafka.send("offlinepay.payment-committed.v1",claim.id().toString(),claim.payload()).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1,notices.list(user.userId(),0,20).size()));
        assertEquals("PUBLISHED",outbox.findById(payment.transactionId()).orElseThrow().getStatus());
        kafka.send("offlinepay.payment-committed.v1","poison","not-json").get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(1,
            jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_event WHERE event_key='poison'",Integer.class)));
        assertEquals(1,notices.list(user.userId(),0,20).size());
        assertEquals("IN_APP_ONLY",jdbc.queryForObject("SELECT delivery_status FROM user_notification WHERE event_id=?",String.class,claim.id()));
    }
}
