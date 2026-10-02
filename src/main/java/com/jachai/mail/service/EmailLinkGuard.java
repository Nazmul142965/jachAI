package com.jachai.mail.service;

import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class EmailLinkGuard {
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final CampaignEmailInvitationRepository invitations;
    private final CertificateRepository certificates;
    private final UserRepository users;

    public boolean isCurrent(EmailPayload payload) {
        if (payload.type() == EmailJobType.CERTIFICATE_ISSUED) {
            return currentCertificate(payload);
        }
        String token = tokenFrom(payload.url(),
                payload.type() == EmailJobType.ASSESSMENT_INVITATION
                        ? "invitation" : "token");
        if (token == null) return false;
        String hash = hash(token);
        Instant now = Instant.now();
        return switch (payload.type()) {
            case VERIFICATION -> verificationTokens.findByTokenHash(hash)
                    .filter(value -> value.expiresAt().isAfter(now)).isPresent();
            case PASSWORD_RESET -> resetTokens.findByTokenHash(hash)
                    .filter(value -> value.expiresAt().isAfter(now)).isPresent();
            case ASSESSMENT_INVITATION -> invitations.findByTokenHash(hash)
                    .filter(value -> value.acceptedAt() == null && value.expiresAt().isAfter(now)).isPresent();
            case CERTIFICATE_ISSUED -> false;
        };
    }

    private boolean currentCertificate(EmailPayload payload) {
        if (payload.resourceId() == null) return false;
        try {
            String path = URI.create(payload.url()).getPath();
            return certificates.findById(payload.resourceId())
                    .filter(certificate -> certificate.status() == CertificateStatus.ISSUED
                            && path.equals("/certificates/" + certificate.verificationCode()))
                    .flatMap(certificate -> users.findById(certificate.learnerUserId()))
                    .filter(user -> user.email().equalsIgnoreCase(payload.recipient()))
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String tokenFrom(String url, String parameter) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return null;
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && parameter.equals(pair[0])) {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return null;
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
