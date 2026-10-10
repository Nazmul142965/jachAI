package com.jachai.support.model;

import com.jachai.support.type.TicketCategory;
import com.jachai.support.type.TicketStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A support ticket. Tickets are owned by a user (the requester) and may optionally
 * reference an organization the requester actually belongs to. Tickets are global
 * (not tenant-scoped) — a PLATFORM_ADMIN operator can see all of them.
 */
@Document("support_tickets")
@CompoundIndexes({
    @CompoundIndex(name = "ticket_owner_created", def = "{ownerUserId:1, createdAt:-1}"),
    @CompoundIndex(name = "ticket_status_created", def = "{status:1, createdAt:-1}"),
    @CompoundIndex(name = "ticket_organization", def = "{organizationId:1, status:1, createdAt:-1}")
})
public class SupportTicket {
    @Id
    private String id;

    private String referenceNumber;

    private String ownerUserId;

    /** Optional. Null when the requester has no organization or chooses not to share one. */
    private String organizationId;

    private TicketCategory category;

    private String subject;

    private String description;

    private TicketStatus status;

    /** Append-only list of reply IDs. Order is preserved. */
    private List<String> replyIds;

    private String lastReplyByUserId;

    private String lastReplyBodyPreview;

    private Instant lastReplyAt;

    /** Set by the operator when the ticket is closed. Null until then. */
    private Instant closedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public SupportTicket() {}

    public SupportTicket(String id, String referenceNumber, String ownerUserId,
                         String organizationId, TicketCategory category, String subject,
                         String description, TicketStatus status, List<String> replyIds,
                         String lastReplyByUserId, String lastReplyBodyPreview,
                         Instant lastReplyAt, Instant closedAt, Instant createdAt,
                         Instant updatedAt) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.ownerUserId = ownerUserId;
        this.organizationId = organizationId;
        this.category = category;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.replyIds = replyIds;
        this.lastReplyByUserId = lastReplyByUserId;
        this.lastReplyBodyPreview = lastReplyBodyPreview;
        this.lastReplyAt = lastReplyAt;
        this.closedAt = closedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getReferenceNumber() { return referenceNumber; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getOrganizationId() { return organizationId; }
    public TicketCategory getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public TicketStatus getStatus() { return status; }
    public List<String> getReplyIds() { return replyIds; }
    public String getLastReplyByUserId() { return lastReplyByUserId; }
    public String getLastReplyBodyPreview() { return lastReplyBodyPreview; }
    public Instant getLastReplyAt() { return lastReplyAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(TicketStatus status) { this.status = status; }
    public void setReplyIds(List<String> replyIds) { this.replyIds = replyIds; }
    public void setLastReplyByUserId(String lastReplyByUserId) { this.lastReplyByUserId = lastReplyByUserId; }
    public void setLastReplyBodyPreview(String lastReplyBodyPreview) { this.lastReplyBodyPreview = lastReplyBodyPreview; }
    public void setLastReplyAt(Instant lastReplyAt) { this.lastReplyAt = lastReplyAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public void setId(String id) { this.id = id; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public void setCategory(TicketCategory category) { this.category = category; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setDescription(String description) { this.description = description; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}