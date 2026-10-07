package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.model.WorkspaceJoinCode;
import com.jachai.organization.model.WorkspaceJoinRedemption;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.repository.WorkspaceJoinCodeRepository;
import com.jachai.organization.repository.WorkspaceJoinRedemptionRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class WorkspaceJoinCodeServiceTests {
    private static final String RAW_CODE = "ABCD-EFGH-JKLM-NPQR";

    @Mock WorkspaceJoinCodeRepository codes;
    @Mock WorkspaceJoinRedemptionRepository redemptions;
    @Mock OrganizationRepository organizations;
    @Mock MembershipRepository memberships;
    @Mock UserRepository users;
    @Mock MembershipService memberService;
    @Mock TenantAccessService access;
    @Mock MongoTemplate mongo;

    private WorkspaceJoinCodeService service() {
        return new WorkspaceJoinCodeService(codes, redemptions, organizations, memberships,
                users, memberService, access, mongo);
    }

    private WorkspaceJoinCode code(Instant expiresAt) {
        return new WorkspaceJoinCode("code", "org", OrganizationRole.LEARNER, "hash",
                "owner", Instant.now(), expiresAt, null, 2, 0);
    }

    private void memberContext(WorkspaceJoinCode code) {
        when(codes.findByCodeHash(any())).thenReturn(Optional.of(code));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", "hash", true, false, 0, Instant.now())));
        when(organizations.findById("org")).thenReturn(Optional.of(new Organization("org", "Academy",
                OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    @Test
    void successfulJoinCountsOnceOnlyAfterMembershipAndKeepsLegacyCount() {
        WorkspaceJoinCode code = code(Instant.now().plusSeconds(3600));
        memberContext(code);
        when(redemptions.findById("code:learner")).thenReturn(Optional.empty());
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(WorkspaceJoinCode.class))).thenReturn(code);
        when(redemptions.insert(any(WorkspaceJoinRedemption.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(mongo.updateFirst(any(Query.class), any(Update.class),
                eq(WorkspaceJoinRedemption.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertEquals(OrganizationRole.LEARNER, service().join("learner", RAW_CODE).myRole());

        ArgumentCaptor<Update> claim = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), claim.capture(), any(FindAndModifyOptions.class),
                eq(WorkspaceJoinCode.class));
        assertEquals(1L, claim.getValue().getUpdateObject().get("$inc", org.bson.Document.class)
                .getLong("claimRevision"));
        verify(memberService).joinWithParticipantCode(any(User.class), any(Organization.class),
                eq(OrganizationRole.LEARNER));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(WorkspaceJoinRedemption.class));

        when(codes.findByOrganizationId(eq("org"), any())).thenReturn(new PageImpl<>(java.util.List.of(code)));
        when(redemptions.countByCodeIdAndCompletedAtIsNotNull("code")).thenReturn(1L);
        assertEquals(3L, service().list("owner", "org", PageRequest.of(0, 20))
                .getContent().getFirst().redemptions());
    }

    @Test
    void failedMembershipDoesNotProduceCompletedRedemption() {
        WorkspaceJoinCode code = code(Instant.now().plusSeconds(3600));
        memberContext(code);
        when(redemptions.findById("code:learner")).thenReturn(Optional.empty());
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(WorkspaceJoinCode.class))).thenReturn(code);
        when(redemptions.insert(any(WorkspaceJoinRedemption.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(memberService.joinWithParticipantCode(any(User.class), any(Organization.class), any()))
                .thenThrow(new IllegalStateException("membership write failed"));

        assertThrows(IllegalStateException.class, () -> service().join("learner", RAW_CODE));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class),
                eq(WorkspaceJoinRedemption.class));
    }

    @Test
    void retryFinishesPendingRedemptionAfterMembershipWasCreated() {
        WorkspaceJoinCode expired = code(Instant.now().minusSeconds(1));
        memberContext(expired);
        WorkspaceJoinRedemption pending = new WorkspaceJoinRedemption("code:learner", "code",
                "learner", Instant.now().minusSeconds(60), null, Instant.now().plusSeconds(3600));
        when(redemptions.findById("code:learner")).thenReturn(Optional.of(pending));
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, true, Instant.now())));
        when(mongo.updateFirst(any(Query.class), any(Update.class),
                eq(WorkspaceJoinRedemption.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertEquals("org", service().join("learner", RAW_CODE).id());
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(WorkspaceJoinCode.class));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(WorkspaceJoinRedemption.class));
    }

    @Test
    void racedRetryReusesTheSameCodeUserRedemption() {
        WorkspaceJoinCode code = code(Instant.now().plusSeconds(3600));
        memberContext(code);
        WorkspaceJoinRedemption pending = new WorkspaceJoinRedemption("code:learner", "code",
                "learner", Instant.now(), null, Instant.now().plusSeconds(7200));
        when(redemptions.findById("code:learner"))
                .thenReturn(Optional.empty(), Optional.of(pending));
        when(redemptions.insert(any(WorkspaceJoinRedemption.class)))
                .thenThrow(new DuplicateKeyException("concurrent insert"));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(WorkspaceJoinCode.class))).thenReturn(code);
        when(mongo.updateFirst(any(Query.class), any(Update.class),
                eq(WorkspaceJoinRedemption.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service().join("learner", RAW_CODE);

        verify(redemptions).insert(any(WorkspaceJoinRedemption.class));
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(WorkspaceJoinRedemption.class));
    }
}
