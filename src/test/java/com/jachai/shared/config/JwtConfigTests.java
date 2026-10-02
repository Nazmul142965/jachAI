package com.jachai.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.AccessRevocationService;

import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;

class JwtConfigTests {
    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = config.jwtSigningKey("test-only-key-at-least-32-utf8-bytes-long");
    private final UserRepository users = mock(UserRepository.class);
    private final AccessRevocationService revocations = mock(AccessRevocationService.class);

    private String token(String purpose) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("jachai").subject("u1")
                .issuedAt(now).expiresAt(now.plusSeconds(900)).id("jti-1")
                .claim("purpose", purpose).claim("sv", 0).build();
        return config.jwtEncoder(key).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void accessTokenWithExpectedPurposeIsAccepted() {
        when(users.findById("u1")).thenReturn(Optional.of(user(0)));
        assertEquals("u1", config.jwtDecoder(key, users, revocations)
                .decode(token("access")).getSubject());
    }

    @Test
    void differentlyPurposedTokenIsRejected() {
        assertThrows(JwtValidationException.class,
                () -> config.jwtDecoder(key, users, revocations).decode(token("refresh")));
    }

    @Test
    void revokedTokenIsRejected() {
        when(users.findById("u1")).thenReturn(Optional.of(user(0)));
        when(revocations.isRevoked("jti-1")).thenReturn(true);
        assertThrows(JwtValidationException.class,
                () -> config.jwtDecoder(key, users, revocations).decode(token("access")));
    }

    @Test
    void passwordResetVersionInvalidatesExistingToken() {
        when(users.findById("u1")).thenReturn(Optional.of(user(1)));
        assertThrows(JwtValidationException.class,
                () -> config.jwtDecoder(key, users, revocations).decode(token("access")));
    }

    private User user(int securityVersion) {
        return new User("u1", "test@example.com", "Test", "hash", true, false,
                securityVersion, Instant.now());
    }
}
