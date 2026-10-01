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
import com.jachai.auth.model.RefreshSession;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {
    @Mock UserRepository users;
    @Mock EmailVerificationTokenRepository tokens;
    @Mock MongoTemplate mongo;
    @Mock EmailSender emailSender;
    @Mock JwtService jwt;
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, tokens, mongo, new BCryptPasswordEncoder(),
                emailSender, jwt, "http://localhost:8080");
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
        assertEquals(64, token.getValue().tokenHash().length());
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
    void unverifiedUserCannotReceiveJwt() {
        String hash = new BCryptPasswordEncoder().encode("long-password-123");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(
                new User("u1", "test@example.com", "Test", hash, false, false, Instant.now())));
        ApiException error = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("test@example.com", "long-password-123")));
        assertEquals("EMAIL_UNVERIFIED", error.code());
    }

    @Test
    void refreshTokenIsConsumedAndRotated() {
        when(mongo.findAndRemove(any(Query.class), eq(RefreshSession.class)))
                .thenReturn(new RefreshSession("session-1", TokenSecrets.hash("old-refresh"),
                        "u1", Instant.now().minusSeconds(30), Instant.now().plusSeconds(300)));
        when(users.findById("u1")).thenReturn(Optional.of(new User("u1", "test@example.com",
                "Test", "hash", true, false, Instant.now())));
        when(jwt.issueAccessToken("u1")).thenReturn("signed-access");
        when(jwt.accessLifetimeSeconds()).thenReturn(900L);

        var response = service.refresh("old-refresh");

        assertEquals("signed-access", response.accessToken());
        assertFalse(response.refreshToken().equals("old-refresh"));
        ArgumentCaptor<RefreshSession> saved = ArgumentCaptor.forClass(RefreshSession.class);
        verify(mongo).insert(saved.capture());
        assertEquals(TokenSecrets.hash(response.refreshToken()), saved.getValue().tokenHash());
    }
}
