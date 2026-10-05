package com.jachai.mail.service;

import com.jachai.mail.model.EmailPayload;

public interface EmailTransport {
    enum Mode { SMTP, LOCAL_CONSOLE }

    Mode mode();
    void deliver(EmailPayload payload);
}
