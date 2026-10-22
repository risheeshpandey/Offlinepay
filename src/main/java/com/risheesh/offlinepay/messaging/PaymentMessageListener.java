package com.risheesh.offlinepay.messaging;

import com.risheesh.offlinepay.notification.NotificationInbox;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "offlinepay.messaging.enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "offlinepay.notifications.local-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentMessageListener {
    private final NotificationInbox inbox;
    private final DeadLetterStore deadLetters;

    @KafkaListener(topics = "${offlinepay.messaging.topic}", groupId = "offlinepay-notifications-v1")
    public void payment(ConsumerRecord<String, String> record) {
        inbox.accept(record.key(), record.value());
    }
    @KafkaListener(topics = "${offlinepay.messaging.topic}.DLT", groupId = "offlinepay-dlt-archive-v1",
            containerFactory = "deadLetterContainerFactory")
    public void deadLetter(ConsumerRecord<String, String> record) {
        deadLetters.archive(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
