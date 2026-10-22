package com.risheesh.offlinepay.messaging;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration @EnableScheduling
public class MessagingConfiguration {
    @Configuration
    @ConditionalOnProperty(name = "offlinepay.messaging.enabled", havingValue = "true")
    static class KafkaConfiguration {
        @Bean NewTopic paymentTopic(@Value("${offlinepay.messaging.topic}") String topic) {
            return TopicBuilder.name(topic).partitions(3).replicas(1).build();
        }
        @Bean NewTopic paymentDlt(@Value("${offlinepay.messaging.topic}") String topic) {
            return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
        }
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka,
                @Value("${offlinepay.messaging.topic}") String topic) {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(consumers);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.getContainerProperties().setObservationEnabled(true);
            var recoverer = new DeadLetterPublishingRecoverer(kafka,
                    (record, error) -> new TopicPartition(topic + ".DLT", record.partition()));
            recoverer.setFailIfSendResultIsError(true);
            var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3));
            errors.addNotRetryableExceptions(InvalidPaymentMessage.class);
            factory.setCommonErrorHandler(errors);
            return factory;
        }
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> deadLetterContainerFactory(
                ConsumerFactory<String, String> consumers) {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(consumers);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.getContainerProperties().setObservationEnabled(true);
            // A DLT storage failure must not disappear into another DLT or be acknowledged.
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2000, FixedBackOff.UNLIMITED_ATTEMPTS)));
            return factory;
        }
    }
}
