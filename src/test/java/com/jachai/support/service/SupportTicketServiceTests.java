package com.jachai.support.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.model.User;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitService;
import com.jachai.support.dto.CreateSupportReplyRequest;
import com.jachai.support.dto.CreateSupportTicketRequest;
import com.jachai.support.model.SupportTicket;
import com.jachai.support.model.SupportTicketReply;
import com.jachai.support.repository.SupportTicketReplyRepository;
import com.jachai.support.repository.SupportTicketRepository;
import com.jachai.support.type.TicketStatus;
import com.jachai.support.type.TicketCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTests {
    @Mock SupportTicketRepository tickets;
    @Mock SupportTicketReplyRepository replies;
    @Mock UserRepository users;
    @Mock MembershipRepository memberships;
    @Mock RateLimitService rateLimiter;
    @Mock EmailNotificationService emailService;

    private SupportTicketService service() {
        return new SupportTicketService(tickets, replies, users, memberships, rateLimiter, emailService);
    }

    private SupportTicket ticket(TicketStatus status) {
        return new SupportTicket("ticket-1", "ABC12345", "owner", "org-a", null,
                "Help", "Details", status, new ArrayList<>(), null, null,
                null, null, Instant.now(), Instant.now());
    }

    @Test
    void anotherUserCannotReadOrReplyToAnOrganizationTicket() {
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket(TicketStatus.RECEIVED)));

        assertEquals("TICKET_ACCESS_DENIED", assertThrows(ApiException.class,
                () -> service().getTicket("other", "ticket-1", false)).code());
        assertEquals("TICKET_ACCESS_DENIED", assertThrows(ApiException.class,
                () -> service().addReply("other", "ticket-1", new CreateSupportReplyRequest("Hi", true), false)).code());
        assertEquals("TICKET_ACCESS_DENIED", assertThrows(ApiException.class,
                () -> service().getPublicReplies("other", "ticket-1", 0)).code());
        verify(replies, never()).insert(any(SupportTicketReply.class));
    }

    @Test
    void cannotAttachAnotherOrganizationToNewTicket() {
        when(users.findById("owner")).thenReturn(Optional.of(new User("owner", "owner@example.test",
                "Owner", "hash", true, false, 0, Instant.now())));

        assertEquals("ORGANIZATION_ACCESS_DENIED", assertThrows(ApiException.class,
                () -> service().createTicket("owner", new CreateSupportTicketRequest(
                        TicketCategory.TECHNICAL, "Help", "Details", "org-b"))).code());
        verify(tickets, never()).insert(any(SupportTicket.class));
        verify(emailService, never()).notifyTicketCreated(any(), any());
    }

    @Test
    void requesterReplyIsPublicAndPersistedBeforeNotification() {
        SupportTicket ticket = ticket(TicketStatus.WORKING_ON_IT);
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(replies.insert(any(SupportTicketReply.class))).thenAnswer(call -> call.getArgument(0));
        when(replies.findById(any())).thenAnswer(call -> Optional.of(new SupportTicketReply(
                call.getArgument(0), "ticket-1", "owner", true, "Details", Instant.now())));

        var view = service().addReply("owner", "ticket-1", new CreateSupportReplyRequest("Details", false), false);

        assertEquals(TicketStatus.WAITING_FOR_YOUR_REPLY, view.status());
        var saved = ArgumentCaptor.forClass(SupportTicketReply.class);
        verify(replies).insert(saved.capture());
        assertEquals(true, saved.getValue().isPublicReply());
        assertEquals(saved.getValue().getId(), ticket.getReplyIds().getFirst());
        verify(tickets).save(ticket);
        verify(emailService).notifyReplyAdded(ticket, "owner", true);
    }

    @Test
    void closedTicketRejectsRepliesAndOnlyResolvedTicketCanCloseOrReopen() {
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket(TicketStatus.CLOSED)));
        assertEquals("TICKET_CLOSED", assertThrows(ApiException.class,
                () -> service().addReply("owner", "ticket-1", new CreateSupportReplyRequest("Hi", true), false)).code());
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket(TicketStatus.RECEIVED)));
        assertEquals("INVALID_STATUS_TRANSITION", assertThrows(ApiException.class,
                () -> service().changeStatus("operator", "ticket-1", TicketStatus.CLOSED)).code());
        assertEquals("INVALID_STATUS_TRANSITION", assertThrows(ApiException.class,
                () -> service().reopenTicket("owner", "ticket-1")).code());
        verify(replies, never()).insert(any(SupportTicketReply.class));
    }

    @Test
    void resolvedTicketReopensOnlyForOwner() {
        SupportTicket ticket = ticket(TicketStatus.RESOLVED);
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket));
        assertEquals("TICKET_ACCESS_DENIED", assertThrows(ApiException.class,
                () -> service().reopenTicket("other", "ticket-1")).code());
        assertEquals(TicketStatus.WAITING_FOR_YOUR_REPLY,
                service().reopenTicket("owner", "ticket-1").status());
        verify(tickets).save(ticket);
    }

    @Test
    void notificationFailureIsPropagatedAfterReplyPersistence() {
        SupportTicket ticket = ticket(TicketStatus.RECEIVED);
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket));
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(emailService).notifyReplyAdded(ticket, "owner", true);

        assertThrows(IllegalStateException.class, () -> service().addReply(
                "owner", "ticket-1", new CreateSupportReplyRequest("Details", true), false));
        verify(replies).insert(any(SupportTicketReply.class));
        verify(tickets).save(ticket);
    }
}
