package com.risheesh.offlinepay.messaging;

public class InvalidPaymentMessage extends RuntimeException {
    public InvalidPaymentMessage() { super("Invalid payment event"); }
}
