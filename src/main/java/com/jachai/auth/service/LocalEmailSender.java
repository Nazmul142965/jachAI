package com.jachai.auth.service;

import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.service.EmailTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LocalEmailSender implements EmailTransport {
    private static final Logger log = LoggerFactory.getLogger(LocalEmailSender.class);

    @Override
    public Mode mode() { return Mode.LOCAL_CONSOLE; }

    @Override
    public void deliver(EmailPayload payload) {
        // Development only: production uses SMTP and never logs links.
        log.info("Development {} email for {}: {}", payload.type(), payload.recipient(), payload.url());
    }
}
