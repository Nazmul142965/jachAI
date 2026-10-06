package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class GoogleIdTokenVerifierTests {
    private final JwtDecoder decoder = Mockito.mock(JwtDecoder.class);
    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier("jachai-client", decoder);

    @Test
    void acceptsVerifiedGoogleAccountWithCorrectAudienceAndIssuer() {
        when(decoder.decode("token")).thenReturn(token("jachai-client", "https://accounts.google.com",
                "learner@gmail.com", true));

        var profile = verifier.verify("token");

        assertEquals("google-sub", profile.subject());
        assertEquals("learner@gmail.com", profile.email());
        assertTrue(profile.authoritativeEmail());
    }

    @Test
    void rejectsTokenForAnotherApplication() {
        when(decoder.decode("token")).thenReturn(token("other-client", "https://accounts.google.com",
                "learner@gmail.com", true));
        assertEquals("INVALID_GOOGLE_TOKEN", assertThrows(ApiException.class,
                () -> verifier.verify("token")).code());
    }

    @Test
    void rejectsTokenSharedWithAnotherAudienceEvenWhenOursIsListed() {
        when(decoder.decode("token")).thenReturn(token(List.of("jachai-client", "other-client"), null));
        assertEquals("INVALID_GOOGLE_TOKEN", assertThrows(ApiException.class,
                () -> verifier.verify("token")).code());
    }

    @Test
    void rejectsDifferentAuthorizedPresenter() {
        when(decoder.decode("token")).thenReturn(token(List.of("jachai-client"), "other-client"));
        assertEquals("INVALID_GOOGLE_TOKEN", assertThrows(ApiException.class,
                () -> verifier.verify("token")).code());
        when(decoder.decode("token")).thenReturn(token(List.of("jachai-client"), "jachai-client"));
        assertTrue(verifier.verify("token").authoritativeEmail());
    }

    @Test
    void rejectsUnverifiedEmailAndWrongIssuer() {
        when(decoder.decode("token")).thenReturn(token("jachai-client", "https://accounts.google.com",
                "learner@gmail.com", false));
        assertThrows(ApiException.class, () -> verifier.verify("token"));
        when(decoder.decode("token")).thenReturn(token("jachai-client", "https://attacker.example",
                "learner@gmail.com", true));
        assertThrows(ApiException.class, () -> verifier.verify("token"));
    }

    @Test
    void thirdPartyEmailRequiresJachaiVerificationBeforeNewAccount() {
        when(decoder.decode("token")).thenReturn(token("jachai-client", "https://accounts.google.com",
                "learner@example.com", true));
        assertFalse(verifier.verify("token").authoritativeEmail());
    }

    private Jwt token(String audience, String issuer, String email, boolean verified) {
        return new Jwt("token", Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                java.util.Map.of("alg", "RS256"), java.util.Map.of(
                "iss", issuer, "aud", List.of(audience), "sub", "google-sub",
                "email", email, "email_verified", verified, "name", "Learner"));
    }

    private Jwt token(List<String> audiences, String authorizedParty) {
        var claims = new HashMap<String, Object>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", audiences);
        claims.put("sub", "google-sub");
        claims.put("email", "learner@gmail.com");
        claims.put("email_verified", true);
        claims.put("name", "Learner");
        if (authorizedParty != null) claims.put("azp", authorizedParty);
        return new Jwt("token", Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                java.util.Map.of("alg", "RS256"), claims);
    }
}
