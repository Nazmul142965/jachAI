package com.jachai.support.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A reply on a support ticket. Replies are append-only — they are never modified or deleted.
 *
 * <p>Each reply is either a public reply (visible to the ticket requester) or an internal
 * note (visible only to PLATFORM_ADMIN operators). Public replies are what the customer
 * sees; internal notes are what operators use to coordinate among themselves without
 * exposing internal discussion to the requester.</p>
 */
@Document("support_ticket_replies")
@CompoundIndexes({
    @CompoundIndex(name = "reply_ticket_index", def = "{ticketId:1, createdAt:1}")
})
public class SupportTicketReply {
    @Id
    private String id;

    private String ticketId;

    private String authorUserId;

    /** True for public replies visible to the requester. False for internal operator notes. */
    private boolean publicReply;

    private String body;

    @CreatedDate
    private Instant createdAt;

    public SupportTicketReply() {}

    public SupportTicketReply(String id, String ticketId, String authorUserId,
                              boolean publicReply, String body, Instant createdAt) {
        this.id = id;
        this.ticketId = ticketId;
        this.authorUserId = authorUserId;
        this.publicReply = publicReply;
        this.body = body;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTicketId() { return ticketId; }
    public String getAuthorUserId() { return authorUserId; }
    public boolean isPublicReply() { return publicReply; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(String id) { this.id = id; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public void setAuthorUserId(String authorUserId) { this.authorUserId = authorUserId; }
    public void setPublicReply(boolean publicReply) { this.publicReply = publicReply; }
    public void setBody(String body) { this.body = body; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}