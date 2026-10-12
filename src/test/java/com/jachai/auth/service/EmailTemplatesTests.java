package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmailTemplatesTests {
    private static EmailPayload payload(EmailJobType type, String name, String title) {
        return new EmailPayload(type, "a@example.com", name, title,
                "https://jachai.example/go?x=1&y=2", Instant.now().plusSeconds(3600));
    }

    @Test
    void everyEmailTypeHasASubjectAButtonAndAPlainTextTwin() {
        for (EmailJobType type : EmailJobType.values()) {
            EmailTemplates.Rendered mail = EmailTemplates.render(payload(type, "Rafi", "TEACHER"));
            assertFalse(mail.subject().isBlank(), type + " subject");
            assertTrue(mail.html().contains("href=\"https://jachai.example/go?x=1&amp;y=2\""), type + " button link");
            assertTrue(mail.text().contains("https://jachai.example/go?x=1&y=2"), type + " plain link");
            assertTrue(mail.html().startsWith("<!doctype html>"));
        }
    }

    @Test
    void namesAndTitlesFromPeopleAreEscapedAndCannotBreakTheSubject() {
        EmailTemplates.Rendered mail = EmailTemplates.render(
                payload(EmailJobType.ORGANIZATION_INVITATION, "<script>x</script>\nBcc: evil@example.com", "ORG_ADMIN"));
        assertFalse(mail.html().contains("<script>"));
        assertFalse(mail.subject().contains("\n"));
        assertTrue(mail.subject().startsWith("Join "));
    }

    @Test
    void billingReceiptKeepsLineBreaksOfTheSummary() {
        EmailTemplates.Rendered mail = EmailTemplates.render(
                payload(EmailJobType.BILLING_NOTICE, "Rafi", "Plan: TEAM\nAmount: 10 USD"));
        assertTrue(mail.html().contains("Plan: TEAM<br>Amount: 10 USD"));
        assertEquals("Your JachAI payment receipt", mail.subject());
    }

    @Test
    void writesAPreviewWhenAskedTo() throws Exception {
        String target = System.getProperty("email.preview.dir");
        if (target == null) return;
        Files.createDirectories(Path.of(target));
        for (EmailJobType type : EmailJobType.values()) {
            EmailTemplates.Rendered mail = EmailTemplates.render(payload(type, "Rafi Ahmed", "Plan: TEAM\nAmount: 10 USD"));
            Files.writeString(Path.of(target, type.name().toLowerCase() + ".html"), mail.html());
        }
    }
}
