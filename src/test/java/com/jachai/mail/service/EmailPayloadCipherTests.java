package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmailPayloadCipherTests {
    private final EmailPayloadCipher cipher = new EmailPayloadCipher(
            "test-only-email-encryption-secret-is-over-32-bytes", new ObjectMapper().findAndRegisterModules());

    @Test
    void encryptsSensitiveLinkAndBindsItToJobId() {
        EmailPayload payload = new EmailPayload(EmailJobType.PASSWORD_RESET, "learner@example.com",
                "Learner", null, "https://jachai.example/reset-password.html?token=secret",
                Instant.now().plusSeconds(600));

        var sealed = cipher.seal("job-a", payload);

        assertFalse(sealed.encryptedPayload().contains("secret"));
        assertFalse(sealed.encryptedPayload().contains("learner@example.com"));
        assertEquals(payload, cipher.open("job-a", sealed.encryptedPayload(), sealed.nonce()));
        assertThrows(IllegalStateException.class,
                () -> cipher.open("job-b", sealed.encryptedPayload(), sealed.nonce()));
    }
}
