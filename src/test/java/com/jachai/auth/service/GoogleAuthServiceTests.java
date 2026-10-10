package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.shared.error.ApiException;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTests {
    @Mock GoogleIdTokenVerifier google;
    @Mock UserRepository users;
    @Mock MongoTemplate mongo;
    @Mock AuthService auth;

    private GoogleAuthService service() {
        return new GoogleAuthService(google, users, mongo, auth);
    }

    @Test
    void createsVerifiedGoogleOnlyUserWithoutPasswordForAuthoritativeEmail() {
        when(google.verify("token")).thenReturn(profile("learner@gmail.com", true));
        when(users.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().login("token");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insert(created.capture());
        assertEquals("google-sub", created.getValue().googleSubject());
        assertNull(created.getValue().passwordHash());
        assertEquals(true, created.getValue().emailVerified());
        verify(auth).issueSession(created.getValue());
    }

    @Test
    void signsInExistingVerifiedAccountAndLinksGoogleByMatchingAuthoritativeEmail() {
        when(google.verify("token")).thenReturn(profile("learner@gmail.com", true));
        User existing = user(null);
        when(users.findByEmail("learner@gmail.com")).thenReturn(Optional.of(existing));
        when(mongo.updateFirst(any(Query.class), any(Update.class),
                org.mockito.ArgumentMatchers.eq(User.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service().login("token");

        ArgumentCaptor<User> linked = ArgumentCaptor.forClass(User.class);
        verify(auth).issueSession(linked.capture());
        assertEquals("google-sub", linked.getValue().googleSubject());
    }

    @Test
    void thirdPartyEmailCannotCreateUnverifiedShortcutAccount() {
        when(google.verify("token")).thenReturn(profile("learner@example.com", false));

        assertEquals("EMAIL_VERIFICATION_REQUIRED", assertThrows(ApiException.class,
                () -> service().login("token")).code());
    }

    @Test
    void disabledLinkedAccountCannotLogin() {
        when(google.verify("token")).thenReturn(profile("learner@gmail.com", true));
        User user = new User("u1", "learner@gmail.com", "Learner", null, true, true,
                0, Instant.now(), "google-sub");
        when(users.findByGoogleSubject("google-sub")).thenReturn(Optional.of(user));

        assertEquals("ACCOUNT_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service().login("token")).code());
    }

    @Test
    void linkRequiresSameVerifiedEmailAndUsesConditionalUpdate() {
        when(google.verify("token")).thenReturn(profile("learner@gmail.com", true));
        when(users.findById("u1")).thenReturn(Optional.of(user(null)));
        when(mongo.updateFirst(any(Query.class), any(Update.class),
                org.mockito.ArgumentMatchers.eq(User.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service().link("u1", "token");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class),
                org.mockito.ArgumentMatchers.eq(User.class));
        assertEquals("u1", query.getValue().getQueryObject().get("_id"));
        assertEquals("learner@gmail.com", query.getValue().getQueryObject().get("email"));
    }

    @Test
    void linkRejectsDifferentEmail() {
        when(google.verify("token")).thenReturn(profile("someone@gmail.com", true));
        when(users.findById("u1")).thenReturn(Optional.of(user(null)));

        assertEquals("GOOGLE_EMAIL_MISMATCH", assertThrows(ApiException.class,
                () -> service().link("u1", "token")).code());
    }

    private GoogleIdTokenVerifier.GoogleProfile profile(String email, boolean authoritative) {
        return new GoogleIdTokenVerifier.GoogleProfile("google-sub", email, "Learner", authoritative);
    }

    private User user(String subject) {
        return new User("u1", "learner@gmail.com", "Learner", "hash", true, false,
                0, Instant.now(), subject);
    }
}
