package com.risheesh.notifications;

import com.risheesh.offlinepay.messaging.*;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@org.springframework.stereotype.Component @RequiredArgsConstructor
public class KafkaInbox {
    private final RemoteInbox inbox;
    private final DeadLetterStore deadLetters;
    @KafkaListener(topics = "${offlinepay.messaging.topic}", groupId = "offlinepay-notifications-v1")
    public void payment(ConsumerRecord<String, String> record) { inbox.accept(record.key(), record.value()); }
    @KafkaListener(topics = "${offlinepay.messaging.topic}.DLT", groupId = "offlinepay-dlt-archive-v1",
            containerFactory = "deadLetterContainerFactory")
    public void deadLetter(ConsumerRecord<String, String> record) {
        deadLetters.archive(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
