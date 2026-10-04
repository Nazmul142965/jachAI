package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.jachai.mail.service.EmailTransport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mail.javamail.JavaMailSender;

class EmailTransportProfileTests {

    @Test
    void defaultLocalProfileKeepsLinksInDevelopmentTransport() {
        try (var context = context("local")) {
            assertEquals(1, context.getBeansOfType(EmailTransport.class).size());
            assertInstanceOf(LocalEmailSender.class, context.getBean(EmailTransport.class));
        }
    }

    @Test
    void optInLocalSmtpAndProductionSelectOnlySmtpTransport() {
        for (String[] profiles : new String[][] {{"local", "smtp"}, {"prod"}}) {
            try (var context = context(profiles)) {
                assertEquals(1, context.getBeansOfType(EmailTransport.class).size());
                assertInstanceOf(SmtpEmailSender.class, context.getBean(EmailTransport.class));
            }
        }
    }

    private AnnotationConfigApplicationContext context(String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("mail-test", Map.of("app.mail.from", "test@example.test")));
        context.registerBean(JavaMailSender.class, () -> mock(JavaMailSender.class));
        context.register(LocalEmailSender.class, SmtpEmailSender.class);
        context.refresh();
        return context;
    }
}
