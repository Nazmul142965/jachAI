package com.jachai.auth.service;

import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.service.EmailTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class SmtpEmailSender implements EmailTransport {
    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void deliver(EmailPayload payload) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(payload.recipient());
        switch (payload.type()) {
            case VERIFICATION -> {
                mail.setSubject("Verify your JachAI email");
                mail.setText("Hello " + payload.displayName() + ",\n\nVerify your email using this link:\n"
                        + payload.url() + "\n\nThis link expires in 24 hours.");
            }
            case PASSWORD_RESET -> {
                mail.setSubject("Reset your JachAI password");
                mail.setText("Hello " + payload.displayName() + ",\n\nReset your password using this link:\n"
                        + payload.url() + "\n\nThis link expires in 30 minutes. If you did not request it, ignore this email.");
            }
            case ASSESSMENT_INVITATION -> {
                mail.setSubject("Your JachAI assessment invitation");
                mail.setText("You have been invited by " + payload.displayName()
                        + " to the assessment campaign \"" + payload.campaignTitle()
                        + "\".\n\nOpen this link, register or sign in using this email address, "
                        + "and accept the invitation:\n" + payload.url()
                        + "\n\nIf you did not expect this invitation, ignore this email.");
            }
            case ORGANIZATION_INVITATION -> {
                mail.setSubject("Join " + payload.displayName() + " on JachAI");
                mail.setText("You have been invited to join " + payload.displayName()
                        + " as " + payload.campaignTitle().replace('_', ' ')
                        + ".\n\nOpen this link, register or sign in using this email address, "
                        + "and accept the invitation:\n" + payload.url()
                        + "\n\nIf you did not expect this invitation, ignore this email.");
            }
            case CERTIFICATE_ISSUED -> {
                mail.setSubject("Your JachAI certificate is ready");
                mail.setText("Hello " + payload.displayName()
                        + ",\n\nYour certificate has been issued. View and verify it here:\n"
                        + payload.url() + "\n\nIf you did not expect this message, contact your institute.");
            }
            case RESULT_RELEASED -> {
                mail.setSubject("Your JachAI assessment result is available");
                mail.setText("Hello " + payload.displayName()
                        + ",\n\nYour assessment result is available. Sign in and open My assessments to view it:\n"
                        + payload.url() + "\n\nIf you did not expect this message, contact your organization.");
            }
            case ASSIGNMENT_REMINDER -> {
                mail.setSubject("Reminder: your JachAI assessment");
                mail.setText("Hello " + payload.displayName()
                        + ",\n\nYour assessment \"" + payload.campaignTitle()
                        + "\" is waiting for you. Sign in and open My assessments before the availability window ends:\n"
                        + payload.url() + "\n\nIf you already completed it, no action is needed.");
            }
        }
        mailSender.send(mail);
    }
}
