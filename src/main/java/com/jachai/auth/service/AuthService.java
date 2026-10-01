package com.jachai.auth.service;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.model.RefreshSession;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(30);
    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwords;
    private final EmailSender emailSender;
    private final JwtService jwt;
    private final String publicBaseUrl;

    public AuthService(UserRepository users, EmailVerificationTokenRepository tokens,
                       MongoTemplate mongo, PasswordEncoder passwords, EmailSender emailSender,
                       JwtService jwt, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.mongo = mongo;
        this.passwords = passwords;
        this.emailSender = emailSender;
        this.jwt = jwt;
        this.publicBaseUrl = publicBaseUrl;
    }

    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        }
        User user = new User(UUID.randomUUID().toString(), email, request.displayName().trim(),
                passwords.encode(request.password()), false, false, Instant.now());
        try {
            users.save(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        }
        issueVerification(user);
    }

    public void resendVerification(String email) {
        users.findByEmail(normalizeEmail(email))
                .filter(user -> !user.emailVerified() && !user.disabled())
                .ifPresent(this::issueVerification);
    }

    public void verifyEmail(String rawToken) {
        String hash = TokenSecrets.hash(rawToken);
        EmailVerificationToken token = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(hash)), EmailVerificationToken.class);
        if (token == null || !token.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired");
        }
        User user = users.findById(token.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired"));
        if (!user.emailVerified()) {
            users.save(user.verified());
        }
    }

    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null || !passwords.matches(request.password(), user.passwordHash()) || user.disabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid email or password");
        }
        if (!user.emailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", "Verify your email before signing in");
        }
        return issueSession(user.id());
    }

    public AuthResponse refresh(String rawToken) {
        RefreshSession session = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(TokenSecrets.hash(rawToken))), RefreshSession.class);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired");
        }
        User user = users.findById(session.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired"));
        if (!user.emailVerified() || user.disabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired");
        }
        return issueSession(user.id());
    }

    public void logout(String rawToken) {
        mongo.findAndRemove(Query.query(Criteria.where("tokenHash").is(TokenSecrets.hash(rawToken))),
                RefreshSession.class);
    }

    private AuthResponse issueSession(String userId) {
        String rawRefresh = TokenSecrets.generate();
        mongo.insert(new RefreshSession(UUID.randomUUID().toString(), TokenSecrets.hash(rawRefresh),
                userId, Instant.now(), Instant.now().plus(REFRESH_LIFETIME)));
        return new AuthResponse(jwt.issueAccessToken(userId), rawRefresh, "Bearer",
                jwt.accessLifetimeSeconds());
    }

    private void issueVerification(User user) {
        tokens.deleteByUserId(user.id());
        String raw = TokenSecrets.generate();
        tokens.save(new EmailVerificationToken(UUID.randomUUID().toString(), TokenSecrets.hash(raw),
                user.id(), Instant.now().plus(VERIFICATION_LIFETIME)));
        emailSender.sendVerification(user.email(), user.displayName(),
                publicBaseUrl + "/verify-email.html?token=" + raw);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
