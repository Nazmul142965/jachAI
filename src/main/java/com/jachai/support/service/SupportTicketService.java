package com.jachai.support.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import com.jachai.support.dto.CreateSupportReplyRequest;
import com.jachai.support.dto.CreateSupportTicketRequest;
import com.jachai.support.dto.SupportTicketReplyView;
import com.jachai.support.dto.SupportTicketView;
import com.jachai.support.type.TicketCategory;
import com.jachai.support.type.TicketStatus;
import com.jachai.support.model.SupportTicket;
import com.jachai.support.model.SupportTicketReply;
import com.jachai.support.repository.SupportTicketRepository;
import com.jachai.support.repository.SupportTicketReplyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportTicketService {
    private static final int PAGE_SIZE = 20;
    private static final int REFERENCE_NUMBER_LENGTH = 8;

    private final SupportTicketRepository tickets;
    private final SupportTicketReplyRepository replies;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RateLimitService rateLimiter;
    private final EmailNotificationService emailService;

    public SupportTicketService(SupportTicketRepository tickets,
                                SupportTicketReplyRepository replies,
                                UserRepository users,
                                MembershipRepository memberships,
                                RateLimitService rateLimiter,
                                EmailNotificationService emailService) {
        this.tickets = tickets;
        this.replies = replies;
        this.users = users;
        this.memberships = memberships;
        this.rateLimiter = rateLimiter;
        this.emailService = emailService;
    }

    /**
     * Creates a new support ticket for an authenticated user.
     * Optionally associates a workspace the user actually belongs to.
     */
    @Transactional
    public SupportTicketView createTicket(String actorUserId, CreateSupportTicketRequest request) {
        // Rate limit ticket creation per user
        rateLimiter.consume(RateLimitPolicy.SUPPORT_TICKET_CREATE, actorUserId);

        User actor = users.findById(actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
        if (!actor.emailVerified() || actor.disabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_UNAVAILABLE",
                    "Only a verified, active account can create a support ticket");
        }

        // Validate organization membership if provided
        if (request.organizationId() != null && !request.organizationId().isBlank()) {
            Membership membership = memberships.findByUserIdAndOrganizationIdAndActiveTrue(
                    actorUserId, request.organizationId())
                    .orElse(null);
            if (membership == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ACCESS_DENIED",
                        "You do not belong to the specified organization");
            }
        }

        String referenceNumber = generateReferenceNumber();
        SupportTicket ticket = new SupportTicket();
        ticket.setId(UUID.randomUUID().toString());
        ticket.setReferenceNumber(referenceNumber);
        ticket.setOwnerUserId(actorUserId);
        ticket.setOrganizationId(request.organizationId() != null && !request.organizationId().isBlank()
                ? request.organizationId() : null);
        ticket.setCategory(request.category());
        ticket.setSubject(request.subject());
        ticket.setDescription(request.description());
        ticket.setStatus(TicketStatus.RECEIVED);
        ticket.setReplyIds(new java.util.ArrayList<>());
        ticket.setCreatedAt(Instant.now());
        ticket.setUpdatedAt(Instant.now());

        tickets.insert(ticket);

        // Send email notification if SMTP is configured
        emailService.notifyTicketCreated(ticket, actor);

        return toView(ticket, 0, 0);
    }

    /**
     * Creates a reply on a ticket.
     */
    @Transactional
    public SupportTicketView addReply(String actorUserId, String ticketId,
                                      CreateSupportReplyRequest request, boolean isOperator) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found"));

        // Authorization: requester can only reply to their own ticket; operators can reply to any
        if (!isOperator && !ticket.getOwnerUserId().equals(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACCESS_DENIED",
                    "You can only reply to your own tickets");
        }

        // Closed tickets cannot receive new replies (except reopen)
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CLOSED",
                    "Cannot reply to a closed ticket. Reopen it first.");
        }

        // Operators can add internal notes; requesters only public replies
        boolean publicReply = isOperator ? Boolean.TRUE.equals(request.publicReply()) : true;

        SupportTicketReply reply = new SupportTicketReply();
        reply.setId(UUID.randomUUID().toString());
        reply.setTicketId(ticketId);
        reply.setAuthorUserId(actorUserId);
        reply.setPublicReply(publicReply);
        reply.setBody(request.body());
        reply.setCreatedAt(Instant.now());
        replies.insert(reply);

        // Update ticket
        List<String> replyIds = ticket.getReplyIds();
        replyIds.add(reply.getId());
        ticket.setReplyIds(replyIds);
        ticket.setLastReplyByUserId(actorUserId);
        ticket.setLastReplyBodyPreview(previewBody(request.body()));
        ticket.setLastReplyAt(Instant.now());
        ticket.setUpdatedAt(Instant.now());

        // Update status based on who replied
        if (isOperator) {
            if (ticket.getStatus() == TicketStatus.RECEIVED) {
                ticket.setStatus(TicketStatus.WORKING_ON_IT);
            } else if (ticket.getStatus() == TicketStatus.WAITING_FOR_YOUR_REPLY) {
                ticket.setStatus(TicketStatus.WORKING_ON_IT);
            }
        } else {
            if (ticket.getStatus() == TicketStatus.WORKING_ON_IT
                    || ticket.getStatus() == TicketStatus.WAITING_FOR_YOUR_REPLY) {
                ticket.setStatus(TicketStatus.WAITING_FOR_YOUR_REPLY);
            }
        }

        tickets.save(ticket);

        // Send email notification
        emailService.notifyReplyAdded(ticket, actorUserId, publicReply);

        // Count public vs internal replies for the view
        int publicCount = (int) replyIds.stream()
                .map(replies::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(r -> r.isPublicReply())
                .count();
        int internalCount = (int) replyIds.stream()
                .map(replies::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(r -> !r.isPublicReply())
                .count();

        return toView(ticket, publicCount, internalCount);
    }

    /**
     * Changes the status of a ticket. Operator-only.
     */
    @Transactional
    public SupportTicketView changeStatus(String operatorUserId, String ticketId,
                                          TicketStatus newStatus) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found"));

        // Terminal state transition validation
        if (ticket.getStatus().isTerminal()) {
            throw new ApiException(HttpStatus.CONFLICT, "TICKET_CLOSED",
                    "Cannot change status of a closed ticket");
        }
        if (newStatus == TicketStatus.CLOSED && !ticket.getStatus().isReopenable()
                && ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Can only close a resolved ticket");
        }

        ticket.setStatus(newStatus);
        ticket.setUpdatedAt(Instant.now());

        if (newStatus == TicketStatus.CLOSED) {
            ticket.setClosedAt(Instant.now());
        }

        tickets.save(ticket);

        return toView(ticket, countPublicReplies(ticketId), countInternalReplies(ticketId));
    }

    /**
     * Reopens a resolved ticket. Can be done by the requester.
     */
    @Transactional
    public SupportTicketView reopenTicket(String actorUserId, String ticketId) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found"));

        if (!ticket.getOwnerUserId().equals(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACCESS_DENIED",
                    "Only the ticket owner can reopen a ticket");
        }
        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Only a resolved ticket can be reopened");
        }

        ticket.setStatus(TicketStatus.WAITING_FOR_YOUR_REPLY);
        ticket.setUpdatedAt(Instant.now());
        tickets.save(ticket);

        return toView(ticket, countPublicReplies(ticketId), countInternalReplies(ticketId));
    }

    public Page<SupportTicketView> getMyTickets(String ownerUserId, int page) {
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
        return tickets.findByOwnerUserId(ownerUserId, pageable)
                .map(t -> toView(t, countPublicReplies(t.getId()), countInternalReplies(t.getId())));
    }

    public SupportTicketView getTicket(String actorUserId, String ticketId, boolean isOperator) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found"));

        if (!isOperator && !ticket.getOwnerUserId().equals(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACCESS_DENIED",
                    "You can only view your own tickets");
        }

        return toView(ticket, countPublicReplies(ticketId), countInternalReplies(ticketId));
    }

    public Page<SupportTicketReplyView> getPublicReplies(String actorUserId, String ticketId, int page) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ticket not found"));

        if (!isOperator(ticket, actorUserId) && !ticket.getOwnerUserId().equals(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACCESS_DENIED",
                    "You can only view your own tickets");
        }

        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        return replies.findByTicketIdAndPublicReplyTrue(ticketId, pageable)
                .map(this::toReplyView);
    }

    public Page<SupportTicketReplyView> getAllReplies(String operatorUserId, String ticketId, int page) {
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        return replies.findByTicketId(ticketId, pageable)
                .map(this::toReplyView);
    }

    // Operator inbox methods
    public Page<SupportTicketView> getOperatorInbox(String operatorUserId,
                                                     TicketStatus statusFilter,
                                                     String organizationIdFilter,
                                                     int page) {
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<SupportTicket> pageResult;
        if (statusFilter != null && organizationIdFilter != null && !organizationIdFilter.isBlank()) {
            pageResult = tickets.findByStatusAndOrganizationId(statusFilter, organizationIdFilter, pageable);
        } else if (statusFilter != null) {
            pageResult = tickets.findByStatus(statusFilter, pageable);
        } else if (organizationIdFilter != null && !organizationIdFilter.isBlank()) {
            pageResult = tickets.findByOrganizationId(organizationIdFilter, pageable);
        } else {
            pageResult = tickets.findAll(pageable);
        }

        return pageResult.map(t -> toView(t, countPublicReplies(t.getId()), countInternalReplies(t.getId())));
    }

    private boolean isOperator(SupportTicket ticket, String userId) {
        // In practice, check PLATFORM_ADMIN role via PlatformAdminService
        return false; // Placeholder - will be integrated with PlatformAdminService
    }

    private String generateReferenceNumber() {
        String candidate;
        do {
            candidate = UUID.randomUUID().toString().substring(0, REFERENCE_NUMBER_LENGTH).toUpperCase();
        } while (tickets.existsById(candidate));
        return candidate;
    }

    private String previewBody(String body) {
        if (body == null) return "";
        return body.length() > 120 ? body.substring(0, 120) + "…" : body;
    }

    private SupportTicketView toView(SupportTicket ticket, int publicReplyCount, int internalReplyCount) {
        return new SupportTicketView(
                ticket.getId(),
                ticket.getReferenceNumber(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getOrganizationId(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getClosedAt(),
                ticket.getLastReplyBodyPreview(),
                ticket.getLastReplyAt(),
                publicReplyCount,
                internalReplyCount
        );
    }

    private SupportTicketReplyView toReplyView(SupportTicketReply reply) {
        return new SupportTicketReplyView(
                reply.getId(),
                reply.getAuthorUserId(),
                reply.isPublicReply(),
                reply.getBody(),
                reply.getCreatedAt()
        );
    }

    private int countPublicReplies(String ticketId) {
        return (int) replies.countByTicketIdAndPublicReplyTrue(ticketId);
    }

    private int countInternalReplies(String ticketId) {
        return (int) replies.countByTicketIdAndPublicReplyFalse(ticketId);
    }
}