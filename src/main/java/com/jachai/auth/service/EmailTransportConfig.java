package com.jachai.auth.service;

import com.jachai.mail.service.EmailTransport;
import java.util.Locale;
import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
class EmailTransportConfig {
    @Bean
    EmailTransport emailTransport(Environment environment) {
        String host = value(environment, "MAIL_HOST");
        String username = value(environment, "MAIL_USERNAME");
        String password = value(environment, "MAIL_PASSWORD");
        String from = value(environment, "MAIL_FROM");
        String portText = value(environment, "MAIL_PORT");
        boolean required = environment.matchesProfiles("prod", "smtp");
        boolean configured = !host.isBlank() || !username.isBlank() || !password.isBlank()
                || !from.isBlank() || !portText.isBlank();
        if (!configured && !required) return new LocalEmailSender();
        if (host.isBlank() || username.isBlank() || password.isBlank() || from.isBlank()) {
            throw new IllegalStateException("SMTP requires MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM");
        }
        if (host.equalsIgnoreCase("smtp.resend.com")
                && (!username.equals("resend") || from.toLowerCase(Locale.ROOT).endsWith("@gmail.com"))) {
            throw new IllegalStateException("Resend SMTP requires MAIL_USERNAME=resend and a verified-domain MAIL_FROM; use smtp.gmail.com for Gmail");
        }
        int port;
        try {
            port = portText.isBlank() ? 587 : Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("MAIL_PORT must be a number between 1 and 65535");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("MAIL_PORT must be a number between 1 and 65535");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        return new SmtpEmailSender(sender, from);
    }

    private static String value(Environment environment, String name) {
        String value = environment.getProperty(name);
        return value == null ? "" : value.trim();
    }
}
