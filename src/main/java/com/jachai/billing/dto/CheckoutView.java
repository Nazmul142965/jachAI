package com.jachai.billing.dto;

import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;

public record CheckoutView(String orderId, PaymentProvider provider,
                           BigDecimal amountBdt, String checkoutUrl) { }
