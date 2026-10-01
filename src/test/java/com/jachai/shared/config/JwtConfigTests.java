package com.jachai.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
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

    private String token(String purpose) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("jachai").subject("u1")
                .issuedAt(now).expiresAt(now.plusSeconds(900)).id("jti-1")
                .claim("purpose", purpose).build();
        return config.jwtEncoder(key).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void accessTokenWithExpectedPurposeIsAccepted() {
        assertEquals("u1", config.jwtDecoder(key).decode(token("access")).getSubject());
    }

    @Test
    void differentlyPurposedTokenIsRejected() {
        assertThrows(JwtValidationException.class,
                () -> config.jwtDecoder(key).decode(token("refresh")));
    }
}
