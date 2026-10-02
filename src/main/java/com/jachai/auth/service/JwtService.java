package com.jachai.auth.service;

import com.jachai.auth.model.User;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    private final JwtEncoder encoder;

    public JwtService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("jachai")
                .subject(user.id())
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_LIFETIME))
                .id(UUID.randomUUID().toString())
                .claim("purpose", "access")
                .claim("sv", user.securityVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessLifetimeSeconds() {
        return ACCESS_LIFETIME.toSeconds();
    }
}
