package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.organization.repository.MembershipRepository;
import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class UnverifiedAccountCleanupTests {
    @Mock MongoTemplate mongo;
    @Mock MembershipRepository memberships;
    @Mock EmailVerificationTokenRepository tokens;

    @Test
    void removesOnlyOldUnverifiedAccountsWithoutMembership() {
        User old = user("old", Instant.now().minusSeconds(8 * 86400L));
        User protectedUser = user("member", Instant.now().minusSeconds(9 * 86400L));
        when(mongo.find(any(Query.class), eq(User.class))).thenReturn(List.of(old, protectedUser));
        when(memberships.existsByUserId(anyString())).thenAnswer(
                call -> call.getArgument(0).equals("member"));
        when(mongo.remove(any(Query.class), eq(User.class))).thenReturn(DeleteResult.acknowledged(1));

        new UnverifiedAccountCleanup(mongo, memberships, tokens).removeExpiredAccounts();

        ArgumentCaptor<Query> deletion = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(deletion.capture(), eq(User.class));
        var query = deletion.getValue().getQueryObject();
        assertTrue(query.containsValue("old"));
        assertTrue(query.containsKey("emailVerified"));
        assertTrue(query.containsKey("createdAt"));
        verify(tokens).deleteByUserId("old");
        verify(tokens, never()).deleteByUserId("member");
    }

    @Test
    void verificationTokenIsNotRemovedIfAtomicAccountDeletionDidNotMatch() {
        User old = user("old", Instant.now().minusSeconds(8 * 86400L));
        when(mongo.find(any(Query.class), eq(User.class))).thenReturn(List.of(old));
        when(mongo.remove(any(Query.class), eq(User.class))).thenReturn(DeleteResult.acknowledged(0));

        new UnverifiedAccountCleanup(mongo, memberships, tokens).removeExpiredAccounts();

        verify(tokens, never()).deleteByUserId(any());
    }

    private static User user(String id, Instant createdAt) {
        return new User(id, id + "@example.test", "Test", "hash", false, false, 0, createdAt);
    }
}
