package com.jachai.auth.service;

import com.jachai.shared.error.ApiException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Component;

@Component
public class GoogleIdTokenVerifier {
    private final String clientId;
    private final JwtDecoder decoder;

    @Autowired
    public GoogleIdTokenVerifier(@Value("${app.auth.google.client-id:}") String clientId) {
        this(clientId, clientId.isBlank() ? null : NimbusJwtDecoder
                .withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256).build());
    }

    GoogleIdTokenVerifier(String clientId, JwtDecoder decoder) {
        this.clientId = clientId.trim();
        this.decoder = decoder;
    }

    public String clientId() {
        return clientId;
    }

    public GoogleProfile verify(String credential) {
        if (decoder == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_LOGIN_UNAVAILABLE",
                    "Google sign-in is not turned on for this site.");
        }
        Jwt token;
        try {
            token = decoder.decode(credential);
        } catch (JwtException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Google sign-in token is invalid");
        }
        String issuer = token.getIssuer() == null ? "" : token.getIssuer().toString();
        String subject = token.getSubject();
        String email = token.getClaimAsString("email");
        String authorizedParty = token.getClaimAsString("azp");
        if (!("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                || token.getAudience().size() != 1 || !clientId.equals(token.getAudience().get(0))
                || authorizedParty != null && !clientId.equals(authorizedParty)
                || subject == null || subject.isBlank()
                || email == null || email.isBlank()
                || !Boolean.TRUE.equals(token.getClaim("email_verified"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Google sign-in token is invalid");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String hostedDomain = token.getClaimAsString("hd");
        boolean authoritativeEmail = normalizedEmail.endsWith("@gmail.com")
                || hostedDomain != null && !hostedDomain.isBlank();
        String name = token.getClaimAsString("name");
        if (name == null || name.isBlank()) name = normalizedEmail;
        if (name.length() > 80) name = name.substring(0, 80);
        return new GoogleProfile(subject, normalizedEmail, name.trim(), authoritativeEmail);
    }

    public record GoogleProfile(String subject, String email, String displayName,
                                boolean authoritativeEmail) { }
}
