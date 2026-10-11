package com.jachai.billing.model;

public enum PaymentProvider {
    BKASH, SSLCOMMERZ,
    /** JachAI's own checkout page: it looks like a gateway, completes the order, and never takes real money. */
    DEMO
}
