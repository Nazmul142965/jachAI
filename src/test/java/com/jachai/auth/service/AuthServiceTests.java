package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.model.PasswordResetToken;
import com.jachai.auth.model.RefreshSession;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {
    @Mock UserRepository users;
    @Mock EmailVerificationTokenRepository tokens;
    @Mock PasswordResetTokenRepository resetTokens;
    @Mock MongoTemplate mongo;
    @Mock EmailSender emailSender;
    @Mock JwtService jwt;
    @Mock AccessRevocationService revocations;
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, tokens, resetTokens, mongo, new BCryptPasswordEncoder(),
                emailSender, jwt, revocations, "http://localhost:8080");
    }

    @Test
    void registrationHashesPasswordAndStoresOnlyVerificationTokenHash() {
        service.register(new RegisterRequest(" Test@Example.COM ", "Test User", "long-password-123"));

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertEquals("test@example.com", user.getValue().email());
        assertFalse(user.getValue().emailVerified());
        assertFalse(user.getValue().passwordHash().contains("long-password-123"));

        ArgumentCaptor<EmailVerificationToken> token = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokens).save(token.capture());
        assertEquals(user.getValue().id(), token.getValue().userId());
        assertEquals(user.getValue().id(), token.getValue().id());
        assertEquals(64, token.getValue().tokenHash().length());
        ArgumentCaptor<Query> legacyCleanup = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(legacyCleanup.capture(), eq(EmailVerificationToken.class));
        assertEquals(user.getValue().id(), legacyCleanup.getValue().getQueryObject().get("userId"));
        assertEquals(user.getValue().id(), legacyCleanup.getValue().getQueryObject()
                .get("_id", org.bson.Document.class).get("$ne"));
        verify(emailSender).sendVerification(eq("test@example.com"), eq("Test User"),
                org.mockito.ArgumentMatchers.contains("verify-email.html?token="));
    }

    @Test
    void expiredVerificationTokenCannotVerifyAccount() {
        when(mongo.findAndRemove(any(Query.class), eq(EmailVerificationToken.class)))
                .thenReturn(new EmailVerificationToken("id", "hash", "user", Instant.now().minusSeconds(1)));
        ApiException error = assertThrows(ApiException.class, () -> service.verifyEmail("some-token"));
        assertEquals("INVALID_TOKEN", error.code());
    }

    @Test
    void aLegacyVerificationLinkCannotVerifyAfterItsReplacementWasIssued() {
        when(mongo.findAndRemove(any(Query.class), eq(EmailVerificationToken.class)))
                .thenReturn(new EmailVerificationToken("old-random-id", "old-hash", "user",
                        Instant.now().plusSeconds(300)));
        when(tokens.findById("user")).thenReturn(Optional.of(new EmailVerificationToken(
                "user", "new-hash", "user", Instant.now().plusSeconds(300))));

        assertEquals("INVALID_TOKEN", assertThrows(ApiException.class,
                () -> service.verifyEmail("old-link")).code());
        verify(users, never()).findById(any());
    }

    @Test
    void demoAccountCannotChangeOrResetPassword() {
        User demo = new User("demo-account-academy-admin", "academy-admin@demo.jachai.invalid",
                "Academy owner", null, true, false, 0, Instant.now());
        when(users.findById(demo.id())).thenReturn(Optional.of(demo));
        ApiException change = assertThrows(ApiException.class,
                () -> service.changePassword(demo.id(), "old", "new-password"));
        assertEquals("DEMO_PASSWORD_LOCKED", change.code());
        when(users.findByEmail(demo.email())).thenReturn(Optional.of(demo));
        service.requestPasswordReset(demo.email());
        verify(resetTokens, never()).deleteByUserId(demo.id());
        verify(emailSender, never()).sendPasswordReset(any(), any(), any());
    }

    @Test
    void demoAccountCannotRenameTheSharedProfile() {
        User demo = new User("demo-account-learner", "learner@demo.jachai.invalid",
                "Learner", null, true, false, 0, Instant.now());
        when(users.findById(demo.id())).thenReturn(Optional.of(demo));

        assertEquals("DEMO_PROFILE_LOCKED", assertThrows(ApiException.class,
                () -> service.updateDisplayName(demo.id(), "Changed name")).code());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    void verificationUsesConditionalUpdateInsteadOfSavingAStaleUser() {
        User user = new User("u1", "test@example.com", "Test", "hash", false, false,
                0, Instant.now());
        when(mongo.findAndRemove(any(Query.class), eq(EmailVerificationToken.class)))
                .thenReturn(new EmailVerificationToken("t1", "hash", "u1", Instant.now().plusSeconds(300)));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.verifyEmail("raw-token");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class), eq(User.class));
        assertTrue(query.getValue().getQueryObject().containsKey("emailVerified"));
        verify(users, never()).save(any());
    }

    @Test
    void unverifiedUserCannotReceiveJwt() {
        String hash = new BCryptPasswordEncoder().encode("long-password-123");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(
                new User("u1", "test@example.com", "Test", hash, false, false, 0, Instant.now())));
        ApiException error = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("test@example.com", "long-password-123")));
        assertEquals("EMAIL_UNVERIFIED", error.code());
    }

    @Test
    void refreshTokenIsConsumedAndRotated() {
        when(mongo.findAndRemove(any(Query.class), eq(RefreshSession.class)))
                .thenReturn(new RefreshSession("session-1", TokenSecrets.hash("old-refresh"),
                        "u1", 0, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300)));
        when(users.findById("u1")).thenReturn(Optional.of(new User("u1", "test@example.com",
                "Test", "hash", true, false, 0, Instant.now())));
        when(jwt.issueAccessToken(any(User.class))).thenReturn("signed-access");
        when(jwt.accessLifetimeSeconds()).thenReturn(900L);

        var response = service.refresh("old-refresh");

        assertEquals("signed-access", response.accessToken());
        assertFalse(response.refreshToken().equals("old-refresh"));
        ArgumentCaptor<RefreshSession> saved = ArgumentCaptor.forClass(RefreshSession.class);
        verify(mongo).insert(saved.capture());
        assertEquals(TokenSecrets.hash(response.refreshToken()), saved.getValue().tokenHash());
    }

    @Test
    void refreshFromBeforePasswordResetIsRejected() {
        when(mongo.findAndRemove(any(Query.class), eq(RefreshSession.class)))
                .thenReturn(new RefreshSession("session-1", TokenSecrets.hash("old-refresh"),
                        "u1", 0, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300)));
        when(users.findById("u1")).thenReturn(Optional.of(new User("u1", "test@example.com",
                "Test", "hash", true, false, 1, Instant.now())));

        ApiException error = assertThrows(ApiException.class, () -> service.refresh("old-refresh"));

        assertEquals("INVALID_REFRESH", error.code());
        verify(jwt, never()).issueAccessToken(any(User.class));
    }

    @Test
    void logoutRevokesAccessTokenAndOnlyOwnRefreshSession() {
        Instant expiry = Instant.now().plusSeconds(300);

        service.logout("u1", "raw-refresh", "jti-1", expiry);

        verify(revocations).revoke("jti-1", expiry);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndRemove(query.capture(), eq(RefreshSession.class));
        assertEquals("u1", query.getValue().getQueryObject().get("userId"));
        assertEquals(TokenSecrets.hash("raw-refresh"), query.getValue().getQueryObject().get("tokenHash"));
    }

    @Test
    void unknownPasswordResetEmailHasNoObservableAction() {
        service.requestPasswordReset("unknown@example.com");

        verify(resetTokens, never()).save(any());
        verify(emailSender, never()).sendPasswordReset(any(), any(), any());
    }

    @Test
    void passwordResetEmailStoresOnlyTokenHash() {
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(new User(
                "u1", "test@example.com", "Test", "hash", true, false, 0, Instant.now())));

        service.requestPasswordReset(" Test@Example.com ");

        ArgumentCaptor<PasswordResetToken> token = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetTokens).save(token.capture());
        assertEquals("u1", token.getValue().userId());
        assertEquals("u1", token.getValue().id());
        assertEquals(64, token.getValue().tokenHash().length());
        assertEquals(0, token.getValue().securityVersion());
        verify(emailSender).sendPasswordReset(eq("test@example.com"), eq("Test"),
                org.mockito.ArgumentMatchers.contains("reset-password.html?token="));
    }

    @Test
    void expiredResetTokenCannotChangePassword() {
        when(mongo.findAndRemove(any(Query.class), eq(PasswordResetToken.class)))
                .thenReturn(new PasswordResetToken("id", "hash", "u1", Instant.now().minusSeconds(1)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.resetPassword("expired-token", "new-long-password-123"));

        assertEquals("INVALID_TOKEN", error.code());
        verify(users, never()).save(any());
    }

    @Test
    void aLegacyResetLinkCannotChangePasswordAfterReplacement() {
        when(mongo.findAndRemove(any(Query.class), eq(PasswordResetToken.class)))
                .thenReturn(new PasswordResetToken("old-random-id", "old-hash", "u1",
                        Instant.now().plusSeconds(300)));
        when(resetTokens.findById("u1")).thenReturn(Optional.of(new PasswordResetToken(
                "u1", "new-hash", "u1", Instant.now().plusSeconds(300))));

        assertEquals("INVALID_TOKEN", assertThrows(ApiException.class,
                () -> service.resetPassword("old-link", "new-long-password-123")).code());
        verify(users, never()).findById(any());
    }

    @Test
    void resetLinkIssuedBeforePasswordChangeCannotResetAgain() {
        when(mongo.findAndRemove(any(Query.class), eq(PasswordResetToken.class)))
                .thenReturn(new PasswordResetToken("u1", "hash", "u1",
                        Instant.now().plusSeconds(300), 0));
        when(users.findById("u1")).thenReturn(Optional.of(new User("u1", "test@example.com",
                "Test", "changed-hash", true, false, 1, Instant.now())));

        assertEquals("INVALID_TOKEN", assertThrows(ApiException.class,
                () -> service.resetPassword("old-link", "another-password")).code());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(User.class));
    }

    @Test
    void successfulResetChangesHashAndRevokesRefreshSessions() {
        User user = new User("u1", "test@example.com", "Test", "old-hash",
                true, false, 0, Instant.now());
        when(mongo.findAndRemove(any(Query.class), eq(PasswordResetToken.class)))
                .thenReturn(new PasswordResetToken("id", TokenSecrets.hash("raw-reset"),
                        "u1", Instant.now().plusSeconds(300)));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        service.resetPassword("raw-reset", "new-long-password-123");

        ArgumentCaptor<Query> guarded = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> saved = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(guarded.capture(), saved.capture(), eq(User.class));
        assertEquals(0, guarded.getValue().getQueryObject().get("securityVersion"));
        assertTrue(new BCryptPasswordEncoder().matches("new-long-password-123",
                (String) ((org.bson.Document) saved.getValue().getUpdateObject().get("$set"))
                        .get("passwordHash")));
        assertEquals(1, ((org.bson.Document) saved.getValue().getUpdateObject().get("$inc"))
                .get("securityVersion"));
        verify(mongo).remove(any(Query.class), eq(RefreshSession.class));
        verify(resetTokens).deleteByUserId("u1");
    }
}
