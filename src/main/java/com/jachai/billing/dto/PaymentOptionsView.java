package com.jachai.billing.dto;

import java.math.BigDecimal;

public record PaymentOptionsView(boolean sandboxOnly, boolean sslCommerzAvailable,
                                 boolean directBkashAvailable, BigDecimal demoTeamPriceBdt,
                                 boolean demoCheckoutAvailable) { }
