package com.risheesh.offlinepay.messaging;

public interface PaymentPublisher {
    void publish(OutboxTransactions.Claim claim) throws Exception;
}
