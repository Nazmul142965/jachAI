package com.jachai.mail.service;

import com.jachai.mail.model.EmailPayload;

public interface EmailTransport {
    void deliver(EmailPayload payload);
}
