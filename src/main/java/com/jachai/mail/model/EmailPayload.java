package com.jachai.mail.model;

import java.time.Instant;

public record EmailPayload(EmailJobType type, String recipient, String displayName,
                           String campaignTitle, String url, Instant validUntil) { }
