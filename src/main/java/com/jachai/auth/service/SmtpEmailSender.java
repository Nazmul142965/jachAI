package com.jachai.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerification(String recipient, String displayName, String verificationUrl) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipient);
        mail.setSubject("Verify your JachAI email");
        mail.setText("Hello " + displayName + ",\n\nVerify your email using this link:\n"
                + verificationUrl + "\n\nThis link expires in 24 hours.");
        mailSender.send(mail);
    }

    @Override
    public void sendPasswordReset(String recipient, String displayName, String resetUrl) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipient);
        mail.setSubject("Reset your JachAI password");
        mail.setText("Hello " + displayName + ",\n\nReset your password using this link:\n"
                + resetUrl + "\n\nThis link expires in 30 minutes. If you did not request it, ignore this email.");
        mailSender.send(mail);
    }
}
