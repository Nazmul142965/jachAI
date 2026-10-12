package com.jachai.auth.service;

import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.service.EmailTransport;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

final class SmtpEmailSender implements EmailTransport {
    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Mode mode() { return Mode.SMTP; }

    @Override
    public void deliver(EmailPayload payload) {
        EmailTemplates.Rendered email = EmailTemplates.render(payload);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(payload.recipient());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new MailPreparationException(exception);
        }
    }
}
