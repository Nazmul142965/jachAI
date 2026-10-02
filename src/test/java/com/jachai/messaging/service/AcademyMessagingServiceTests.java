package com.jachai.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.messaging.model.ChatMessage;
import com.jachai.messaging.model.Conversation;
import com.jachai.messaging.repository.ChatMessageRepository;
import com.jachai.messaging.repository.ConversationRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AcademyMessagingServiceTests {
    @Mock ConversationRepository conversations;
    @Mock ChatMessageRepository messages;
    @Mock MembershipRepository memberships;
    @Mock OrganizationRepository organizations;
    @Mock UserRepository users;
    @Mock TenantAccessService access;
    AcademyMessagingService service;

    @BeforeEach
    void setUp() {
        service = new AcademyMessagingService(conversations, messages, memberships,
                organizations, users, access);
    }

    @Test
    void instructorCanStartTenantScopedConversationWithLearner() {
        academy("teacher", OrganizationRole.INSTRUCTOR);
        when(users.findById("learner")).thenReturn(Optional.of(user("learner", true, false)));
        when(conversations.insert(any(Conversation.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.create("teacher", "org-a", "learner");

        assertEquals("teacher", result.instructorUserId());
        assertEquals("learner", result.learnerUserId());
        assertEquals("Learner", result.counterpartDisplayName());
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER);
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
    }

    @Test
    void learnerCanStartConversationWithInstructor() {
        academy("learner", OrganizationRole.LEARNER);
        when(users.findById("teacher")).thenReturn(Optional.of(user("teacher", true, false)));
        when(conversations.insert(any(Conversation.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.create("learner", "org-a", "teacher");

        assertEquals("teacher", result.instructorUserId());
        assertEquals("learner", result.learnerUserId());
        verify(access).requireRole("teacher", "org-a", OrganizationRole.INSTRUCTOR);
    }

    @Test
    void outsiderCannotReadOrSendToAnotherConversation() {
        academy("other-learner", OrganizationRole.LEARNER);
        when(conversations.findByIdAndOrganizationId("c1", "org-a"))
                .thenReturn(Optional.of(conversation()));

        ApiException read = assertThrows(ApiException.class,
                () -> service.messages("other-learner", "org-a", "c1", PageRequest.of(0, 20)));
        ApiException send = assertThrows(ApiException.class,
                () -> service.send("other-learner", "org-a", "c1", "Hello"));

        assertEquals("NOT_FOUND", read.code());
        assertEquals("NOT_FOUND", send.code());
        verify(messages, never()).insert(any(ChatMessage.class));
    }

    @Test
    void crossTenantConversationIsNotLoaded() {
        academy("teacher", OrganizationRole.INSTRUCTOR);

        ApiException error = assertThrows(ApiException.class,
                () -> service.messages("teacher", "org-a", "foreign", PageRequest.of(0, 20)));

        assertEquals("NOT_FOUND", error.code());
        verify(conversations).findByIdAndOrganizationId("foreign", "org-a");
        verify(messages, never()).findByOrganizationIdAndConversationId(any(), any(), any());
    }

    @Test
    void messageIsTrimmedStoredSeparatelyAndExpiresAfterNinetyDays() {
        academy("teacher", OrganizationRole.INSTRUCTOR);
        when(conversations.findByIdAndOrganizationId("c1", "org-a"))
                .thenReturn(Optional.of(conversation()));
        when(messages.insert(any(ChatMessage.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.send("teacher", "org-a", "c1", "  Practice interfaces  ");

        assertEquals("Practice interfaces", result.body());
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
        assertEquals(Duration.ofDays(90), Duration.between(saved.getValue().sentAt(),
                saved.getValue().expiresAt()));
    }

    @Test
    void storedMessageLimitRejectsFurtherWrites() {
        academy("learner", OrganizationRole.LEARNER);
        when(conversations.findByIdAndOrganizationId("c1", "org-a"))
                .thenReturn(Optional.of(conversation()));
        when(messages.countByOrganizationIdAndConversationId("org-a", "c1")).thenReturn(500L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.send("learner", "org-a", "c1", "Question"));

        assertEquals("MESSAGE_LIMIT", error.code());
        verify(messages, never()).insert(any(ChatMessage.class));
    }

    @Test
    void partnerDirectoryOnlyQueriesOppositeRoleInSameTenant() {
        academy("teacher", OrganizationRole.INSTRUCTOR);
        var pageable = PageRequest.of(0, 20);
        when(memberships.findByOrganizationIdAndRoleAndActiveTrue("org-a", OrganizationRole.LEARNER,
                pageable)).thenReturn(new PageImpl<>(List.of(new Membership("m1", "learner", "org-a",
                OrganizationRole.LEARNER, true, Instant.now())), pageable, 1));
        when(users.findById("learner")).thenReturn(Optional.of(user("learner", true, false)));

        var result = service.partners("teacher", "org-a", pageable);

        assertEquals("learner", result.getContent().getFirst().userId());
        verify(memberships).findByOrganizationIdAndRoleAndActiveTrue("org-a", OrganizationRole.LEARNER,
                pageable);
    }

    @Test
    void talentMemberCannotUseAcademyMessaging() {
        when(access.requireMember("recruiter", "org-a"))
                .thenReturn(new Membership("m1", "recruiter", "org-a", OrganizationRole.RECRUITER,
                        true, Instant.now()));
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Company", OrganizationType.TALENT, "owner", Instant.now())));

        ApiException error = assertThrows(ApiException.class,
                () -> service.list("recruiter", "org-a", PageRequest.of(0, 20)));

        assertEquals("ACADEMY_ONLY", error.code());
        verify(conversations, never()).findByOrganizationIdAndInstructorUserId(any(), any(), any());
    }

    private void academy(String actorId, OrganizationRole role) {
        when(access.requireMember(actorId, "org-a"))
                .thenReturn(new Membership("m1", actorId, "org-a", role, true, Instant.now()));
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Institute", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    private Conversation conversation() {
        return new Conversation("c1", "org-a", "teacher", "learner", Instant.now());
    }

    private User user(String id, boolean verified, boolean disabled) {
        String name = "learner".equals(id) ? "Learner" : "Teacher";
        return new User(id, id + "@example.com", name, "hash", verified, disabled, 0, Instant.now());
    }
}
