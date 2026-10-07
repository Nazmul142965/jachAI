package com.jachai.support.service;

import com.jachai.auth.model.User;
import com.jachai.auth.service.EmailSender;
import com.jachai.support.model.SupportTicket;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService {
    private final EmailSender emailSender;
    private final String publicBaseUrl;

    public EmailNotificationService(EmailSender emailSender,
                                    @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.emailSender = emailSender;
        this.publicBaseUrl = publicBaseUrl;
    }

    public void notifyTicketCreated(SupportTicket ticket, User requester) {
        String ticketUrl = publicBaseUrl + "/support/tickets/" + ticket.getId();
        emailSender.sendSupportTicketNotification(
                ticket.getId(),
                ticket.getReferenceNumber(),
                requester.email(),
                requester.displayName(),
                "Support ticket " + ticket.getReferenceNumber() + " created",
                ticketUrl,
                Instant.now().plus(Duration.ofDays(7)));
    }

    public void notifyReplyAdded(SupportTicket ticket, String actorUserId, boolean publicReply) {
        if (!publicReply) return; // Don't notify for internal notes

        String ticketUrl = publicBaseUrl + "/support/tickets/" + ticket.getId();
        // Recipient is the ticket owner - in production we'd look up their email
        // For now we pass the owner user ID as the recipient (the email sender will resolve it)
        emailSender.sendSupportTicketNotification(
                ticket.getId(),
                ticket.getReferenceNumber(),
                ticket.getOwnerUserId(), // Will be resolved to email by the email sender
                "User",
                "New reply on ticket " + ticket.getReferenceNumber(),
                ticketUrl,
                Instant.now().plus(Duration.ofDays(7)));
    }
}