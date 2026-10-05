package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jachai.mail.service.EmailTransport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class EmailTransportProfileTests {

    @Test
    void defaultLocalProfileKeepsLinksInDevelopmentTransport() {
        try (var context = context(Map.of(), "local")) {
            assertEquals(1, context.getBeansOfType(EmailTransport.class).size());
            assertInstanceOf(LocalEmailSender.class, context.getBean(EmailTransport.class));
            assertEquals(EmailTransport.Mode.LOCAL_CONSOLE, context.getBean(EmailTransport.class).mode());
        }
    }

    @Test
    void completeMailEnvironmentSelectsSmtpWithoutAnExtraProfile() {
        try (var context = context(mailSettings(), "local")) {
            assertEquals(1, context.getBeansOfType(EmailTransport.class).size());
            assertInstanceOf(SmtpEmailSender.class, context.getBean(EmailTransport.class));
            assertEquals(EmailTransport.Mode.SMTP, context.getBean(EmailTransport.class).mode());
        }
    }

    @Test
    void optInLocalSmtpAndProductionSelectOnlySmtpTransport() {
        for (String[] profiles : new String[][] {{"local", "smtp"}, {"prod"}}) {
            try (var context = context(mailSettings(), profiles)) {
                assertEquals(1, context.getBeansOfType(EmailTransport.class).size());
                assertInstanceOf(SmtpEmailSender.class, context.getBean(EmailTransport.class));
            }
        }
    }

    @Test
    void partialMailEnvironmentFailsInsteadOfSilentlyLoggingVerificationLinks() {
        assertThrows(Exception.class, () -> context(Map.of("MAIL_HOST", "smtp.example.test"), "local"));
    }

    @Test
    void mixedResendAndGmailSettingsFailBeforeSending() {
        assertThrows(Exception.class, () -> context(Map.of(
                "MAIL_HOST", "smtp.resend.com", "MAIL_USERNAME", "person@gmail.com",
                "MAIL_PASSWORD", "test-only-secret", "MAIL_FROM", "person@gmail.com"), "local"));
    }

    private Map<String, Object> mailSettings() {
        return Map.of("MAIL_HOST", "smtp.example.test", "MAIL_PORT", "587",
                "MAIL_USERNAME", "sender@example.test", "MAIL_PASSWORD", "test-only-secret",
                "MAIL_FROM", "sender@example.test");
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> settings, String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("mail-test", settings));
        context.register(EmailTransportConfig.class);
        context.refresh();
        return context;
    }
}
