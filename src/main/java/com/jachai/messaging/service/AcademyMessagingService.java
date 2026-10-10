package com.jachai.messaging.service;

import com.jachai.auth.repository.UserRepository;
import com.jachai.messaging.dto.ChatMessageView;
import com.jachai.messaging.dto.ConversationView;
import com.jachai.messaging.dto.MessagingPartnerView;
import com.jachai.messaging.model.ChatMessage;
import com.jachai.messaging.model.Conversation;
import com.jachai.messaging.repository.ChatMessageRepository;
import com.jachai.messaging.repository.ConversationRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AcademyMessagingService {
    private static final Duration MESSAGE_RETENTION = Duration.ofDays(90);
    private static final long MAX_STORED_MESSAGES_PER_CONVERSATION = 500;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TenantAccessService access;
    private final MongoTemplate mongo;

    public Page<MessagingPartnerView> partners(String actorId, String organizationId, Pageable pageable) {
        OrganizationRole actorRole = requireMessagingRole(actorId, organizationId);
        OrganizationRole partnerRole = actorRole == OrganizationRole.ORG_ADMIN
                ? OrganizationRole.INSTRUCTOR : otherRole(actorRole);
        return memberships.findByOrganizationIdAndRoleAndActiveTrue(organizationId, partnerRole, pageable)
                .map(member -> users.findById(member.userId())
                        .filter(user -> user.emailVerified() && !user.disabled())
                        .map(user -> new MessagingPartnerView(user.id(), user.displayName(), user.email()))
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PARTNER_UNAVAILABLE",
                                "A listed member account is unavailable")));
    }

    public ConversationView create(String actorId, String organizationId, String partnerUserId) {
        OrganizationRole actorRole = requireMessagingRole(actorId, organizationId);
        OrganizationRole partnerRole = actorRole == OrganizationRole.ORG_ADMIN
                ? OrganizationRole.INSTRUCTOR : otherRole(actorRole);
        access.requireRole(partnerUserId, organizationId, partnerRole);
        users.findById(partnerUserId)
                .filter(user -> user.emailVerified() && !user.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PARTNER_UNAVAILABLE",
                        "Partner account is unavailable"));
        String instructorId = actorRole == OrganizationRole.INSTRUCTOR ? actorId
                : actorRole == OrganizationRole.ORG_ADMIN ? partnerUserId : partnerUserId;
        String learnerId = actorRole == OrganizationRole.LEARNER ? actorId
                : actorRole == OrganizationRole.ORG_ADMIN ? actorId : partnerUserId;
        Conversation existing = conversations.findByOrganizationIdAndInstructorUserIdAndLearnerUserId(
                organizationId, instructorId, learnerId).orElse(null);
        if (existing != null) return view(existing, actorId);
        Conversation next = new Conversation(UUID.randomUUID().toString(), organizationId,
                instructorId, learnerId, Instant.now());
        try {
            return view(conversations.insert(next), actorId);
        } catch (DuplicateKeyException exception) {
            return conversations.findByOrganizationIdAndInstructorUserIdAndLearnerUserId(
                            organizationId, instructorId, learnerId)
                    .map(conversation -> view(conversation, actorId))
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CONVERSATION_CONFLICT",
                            "Conversation changed concurrently; reload it"));
        }
    }

    public Page<ConversationView> list(String actorId, String organizationId, Pageable pageable) {
        OrganizationRole role = requireMessagingRole(actorId, organizationId);
        Page<Conversation> page = role == OrganizationRole.INSTRUCTOR
                ? conversations.findByOrganizationIdAndInstructorUserId(organizationId, actorId, pageable)
                : conversations.findByOrganizationIdAndLearnerUserId(organizationId, actorId, pageable);
        return page.map(conversation -> view(conversation, actorId));
    }

    public Page<ChatMessageView> messages(String actorId, String organizationId,
                                          String conversationId, Pageable pageable) {
        ownConversation(actorId, organizationId, conversationId);
        return messages.findByOrganizationIdAndConversationId(organizationId, conversationId, pageable)
                .map(ChatMessageView::from);
    }

    public ChatMessageView send(String actorId, String organizationId, String conversationId,
                                String body) {
        Conversation conversation = ownConversation(actorId, organizationId, conversationId);
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message must contain 1 to 1000 characters");
        }
        if (messages.countByOrganizationIdAndConversationId(organizationId, conversationId)
                >= MAX_STORED_MESSAGES_PER_CONVERSATION) {
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_LIMIT",
                    "Conversation is at its stored-message limit; try again after older messages expire");
        }
        Instant now = Instant.now();
        ChatMessage message = new ChatMessage(UUID.randomUUID().toString(), organizationId,
                conversationId, actorId, trimmed, now, now.plus(MESSAGE_RETENTION));
        ChatMessageView sent = ChatMessageView.from(messages.insert(message));
        // Atomic $set rather than read-modify-write: two people can send at once. Whoever sends
        // has, by definition, read everything up to this moment.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(conversationId)),
                new Update().set("lastMessageAt", now)
                        .set("lastMessagePreview", preview(trimmed))
                        .set(readField(conversation, actorId), now),
                Conversation.class);
        return sent;
    }

    /** The person has opened the thread: everything up to now counts as read. */
    public void markRead(String actorId, String organizationId, String conversationId) {
        Conversation conversation = ownConversation(actorId, organizationId, conversationId);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(conversationId)),
                new Update().set(readField(conversation, actorId), Instant.now()), Conversation.class);
    }

    /** Unread messages across this person's conversations in the organization, for a badge. */
    public int unreadTotal(String actorId, String organizationId) {
        OrganizationRole role = requireMessagingRole(actorId, organizationId);
        // A badge past a hundred conversations says nothing more than "a lot".
        PageRequest first = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Conversation> page = role == OrganizationRole.INSTRUCTOR
                ? conversations.findByOrganizationIdAndInstructorUserId(organizationId, actorId, first)
                : conversations.findByOrganizationIdAndLearnerUserId(organizationId, actorId, first);
        return page.getContent().stream().mapToInt(conversation -> unread(conversation, actorId)).sum();
    }

    private int unread(Conversation conversation, String actorId) {
        Instant lastRead = actorId.equals(conversation.instructorUserId())
                ? conversation.instructorLastReadAt() : conversation.learnerLastReadAt();
        return (int) messages.countByOrganizationIdAndConversationIdAndSenderUserIdNotAndSentAtAfter(
                conversation.organizationId(), conversation.id(), actorId,
                lastRead == null ? Instant.EPOCH : lastRead);
    }

    private static String readField(Conversation conversation, String actorId) {
        return actorId.equals(conversation.instructorUserId()) ? "instructorLastReadAt" : "learnerLastReadAt";
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() <= 80 ? flat : flat.substring(0, 79) + "…";
    }

    private Conversation ownConversation(String actorId, String organizationId, String conversationId) {
        OrganizationRole role = requireMessagingRole(actorId, organizationId);
        Conversation conversation = conversations.findByIdAndOrganizationId(conversationId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found"));
        boolean participant = role == OrganizationRole.INSTRUCTOR
                ? actorId.equals(conversation.instructorUserId())
                : actorId.equals(conversation.learnerUserId());
        if (!participant) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Conversation not found");
        }
        return conversation;
    }

    private OrganizationRole requireMessagingRole(String actorId, String organizationId) {
        OrganizationRole role = access.requireMember(actorId, organizationId).role();
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.ACADEMY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY",
                    "Direct messaging is available in Academy organizations");
        }
        if (role != OrganizationRole.INSTRUCTOR && role != OrganizationRole.LEARNER
                && role != OrganizationRole.ORG_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only Academy owners, instructors and learners can use direct messaging");
        }
        return role;
    }

    private OrganizationRole otherRole(OrganizationRole role) {
        return role == OrganizationRole.INSTRUCTOR ? OrganizationRole.LEARNER : OrganizationRole.INSTRUCTOR;
    }

    private ConversationView view(Conversation conversation, String actorId) {
        String counterpartId = actorId.equals(conversation.instructorUserId())
                ? conversation.learnerUserId() : conversation.instructorUserId();
        String displayName = users.findById(counterpartId)
                .map(user -> user.displayName()).orElse("Former member");
        return new ConversationView(conversation.id(), conversation.instructorUserId(),
                conversation.learnerUserId(), displayName, conversation.createdAt(),
                unread(conversation, actorId), conversation.lastMessageAt(), conversation.lastMessagePreview());
    }
}
