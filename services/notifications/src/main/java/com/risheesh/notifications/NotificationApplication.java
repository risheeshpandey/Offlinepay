package com.risheesh.notifications;

import com.risheesh.offlinepay.common.*;
import com.risheesh.offlinepay.notification.*;
import com.risheesh.offlinepay.messaging.DeadLetterStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(clients = CoreClient.class)
@EnableScheduling
@EntityScan(basePackageClasses = NotificationEntity.class)
@EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
@Import({TimeConfig.class, ApiExceptionHandler.class, RequestLoggingFilter.class,
        NotificationService.class, NotificationDeliveryTransactions.class, NotificationDeliveryWorker.class,
        NotificationWebhook.class, DeadLetterStore.class})
public class NotificationApplication {
    public static void main(String[] args) { SpringApplication.run(NotificationApplication.class, args); }
}
