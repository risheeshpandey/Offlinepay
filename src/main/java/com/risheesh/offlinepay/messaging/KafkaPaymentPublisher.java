package com.risheesh.offlinepay.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "offlinepay.messaging.enabled", havingValue = "true")
public class KafkaPaymentPublisher implements PaymentPublisher {
    private final KafkaTemplate<String, String> kafka;
    @Value("${offlinepay.messaging.topic}") private String topic;
    public void publish(OutboxTransactions.Claim claim) throws Exception {
        kafka.send(topic, claim.id().toString(), claim.payload()).get(10, TimeUnit.SECONDS);
    }
}
