package com.jachai.auth.service;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.model.PasswordResetToken;
import com.jachai.auth.model.RefreshSession;
import com.jachai.auth.model.User;
import com.jachai.auth.model.UserAvatar;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.mongodb.client.result.UpdateResult;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;
import com.jachai.auth.repository.UserAvatarRepository;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration RESET_LIFETIME = Duration.ofMinutes(30);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(30);
    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final EmailVerificationTokenRepository tokens;
    private final PasswordResetTokenRepository resetTokens;
    private final MongoTemplate mongo;
    private final PasswordEncoder passwords;
    private final EmailSender emailSender;
    private final JwtService jwt;
    private final AccessRevocationService revocations;
    private final String publicBaseUrl;

    public record AccountView(String email, String displayName, boolean emailVerified,
                              boolean passwordSet, boolean googleLinked, Instant createdAt,
                              String avatarDataUrl) { }

    @Autowired
    public AuthService(UserRepository users, EmailVerificationTokenRepository tokens,
                       PasswordResetTokenRepository resetTokens,
                       MongoTemplate mongo, PasswordEncoder passwords, EmailSender emailSender,
                       JwtService jwt, AccessRevocationService revocations,
                       UserAvatarRepository avatars,
                       @Value("${app.public-base-url}") String publicBaseUrl) {
        this.users = users;
        this.avatars = avatars;
        this.tokens = tokens;
        this.resetTokens = resetTokens;
        this.mongo = mongo;
        this.passwords = passwords;
        this.emailSender = emailSender;
        this.jwt = jwt;
        this.revocations = revocations;
        this.publicBaseUrl = publicBaseUrl;
    }

    // Keeps focused service tests that do not exercise profile images source-compatible.
    public AuthService(UserRepository users, EmailVerificationTokenRepository tokens,
                       PasswordResetTokenRepository resetTokens, MongoTemplate mongo,
                       PasswordEncoder passwords, EmailSender emailSender, JwtService jwt,
                       AccessRevocationService revocations, String publicBaseUrl) {
        this(users, tokens, resetTokens, mongo, passwords, emailSender, jwt, revocations, null, publicBaseUrl);
    }

    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        }
        User user = new User(UUID.randomUUID().toString(), email, request.displayName().trim(),
                passwords.encode(request.password()), false, false, 0, Instant.now());
        try {
            users.save(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        }
        issueVerification(user);
    }

    public void resendVerification(String email) {
        users.findByEmail(normalizeEmail(email))
                .filter(user -> !user.emailVerified() && !user.disabled()
                        && user.createdAt().isAfter(Instant.now().minus(Duration.ofDays(7))))
                .ifPresent(this::issueVerification);
    }

    public void verifyEmail(String rawToken) {
        String hash = TokenSecrets.hash(rawToken);
        EmailVerificationToken token = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(hash)), EmailVerificationToken.class);
        if (token == null || !token.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired");
        }
        // Older releases used a random document ID. Once a per-user token has been issued,
        // those legacy links must no longer verify the account.
        if (!token.id().equals(token.userId()) && tokens.findById(token.userId()).isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired");
        }
        User user = users.findById(token.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired"));
        if (user.emailVerified()) return;
        UpdateResult updated = mongo.updateFirst(Query.query(Criteria.where("_id").is(user.id())
                        .and("emailVerified").is(false)
                        .and("createdAt").gt(Instant.now().minus(Duration.ofDays(7)))),
                new Update().set("emailVerified", true), User.class);
        if (updated.getMatchedCount() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Verification link is invalid or expired");
        }
    }

    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null || user.passwordHash() == null
                || !passwords.matches(request.password(), user.passwordHash()) || user.disabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid email or password");
        }
        if (!user.emailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", "Verify your email before signing in");
        }
        return issueSession(user);
    }

    public AccountView me(String userId) {
        User user = activeUser(userId);
        String avatar = avatars == null ? null : avatars.findById(user.id())
                .map(value -> "data:" + value.contentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(value.image()))
                .orElse(null);
        return new AccountView(user.email(), user.displayName(), user.emailVerified(),
                user.passwordHash() != null, user.googleSubject() != null, user.createdAt(), avatar);
    }

    public AccountView updateAvatar(String userId, MultipartFile upload) {
        User user = activeUser(userId);
        if (isDemoUser(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_PROFILE_LOCKED",
                    "Demo accounts cannot change their shared profile");
        }
        if (upload == null || upload.isEmpty() || upload.getSize() > 5_000_000L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR",
                    "Choose a non-empty image smaller than 5 MB");
        }
        try {
            BufferedImage source = ImageIO.read(upload.getInputStream());
            if (source == null || source.getWidth() < 1 || source.getHeight() < 1
                    || source.getWidth() > 6000 || source.getHeight() > 6000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR",
                        "The image format or dimensions are not supported");
            }
            int side = Math.max(source.getWidth(), source.getHeight());
            int width = Math.max(1, Math.round(source.getWidth() * Math.min(1f, 256f / side)));
            int height = Math.max(1, Math.round(source.getHeight() * Math.min(1f, 256f / side)));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
            graphics.dispose();
            byte[] encoded = encodeJpeg(resized, 0.78f);
            if (encoded.length > 120_000) encoded = encodeJpeg(resized, 0.55f);
            if (encoded.length > 120_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_TOO_LARGE",
                        "The compressed image is still too large; choose a simpler image");
            }
            avatars.save(new UserAvatar(user.id(), encoded, "image/jpeg", Instant.now()));
            return me(user.id());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR",
                    "The image could not be read");
        }
    }

    public void removeAvatar(String userId) {
        User user = activeUser(userId);
        if (isDemoUser(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_PROFILE_LOCKED",
                    "Demo accounts cannot change their shared profile");
        }
        avatars.deleteById(user.id());
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var output = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    public AccountView updateDisplayName(String userId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 2 || name.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME",
                    "Display name must be between 2 and 80 characters");
        }
        User user = activeUser(userId);
        if (isDemoUser(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_PROFILE_LOCKED",
                    "Demo accounts cannot change their shared profile");
        }
        UpdateResult result = mongo.updateFirst(Query.query(Criteria.where("_id").is(userId)
                        .and("emailVerified").is(true).and("disabled").is(false)),
                new Update().set("displayName", name), User.class);
        if (result.getMatchedCount() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED", "Account changed; reload and try again");
        }
        return me(userId);
    }

    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = activeUser(userId);
        if (isDemoUser(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_PASSWORD_LOCKED",
                    "Demo accounts cannot change passwords");
        }
        if (user.passwordHash() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_NOT_SET",
                    "Password sign-in is not enabled; request a password reset link to set one");
        }
        if (!passwords.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "Current password is incorrect");
        }
        if (passwords.matches(newPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Choose a different password");
        }
        UpdateResult result = mongo.updateFirst(Query.query(Criteria.where("_id").is(user.id())
                        .and("passwordHash").is(user.passwordHash())
                        .and("securityVersion").is(user.securityVersion())
                        .and("emailVerified").is(true).and("disabled").is(false)),
                new Update().set("passwordHash", passwords.encode(newPassword))
                        .inc("securityVersion", 1), User.class);
        if (result.getMatchedCount() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED", "Account changed; reload and try again");
        }
        resetTokens.deleteByUserId(user.id());
        mongo.remove(Query.query(Criteria.where("userId").is(user.id())), RefreshSession.class);
    }

    private User activeUser(String userId) {
        return users.findById(userId)
                .filter(user -> user.emailVerified() && !user.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE",
                        "Account is unavailable"));
    }

    public AuthResponse refresh(String rawToken) {
        RefreshSession session = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(TokenSecrets.hash(rawToken))), RefreshSession.class);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired");
        }
        User user = users.findById(session.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired"));
        if (!user.emailVerified() || user.disabled()
                || session.securityVersion() != user.securityVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Refresh session is invalid or expired");
        }
        return issueSession(user);
    }

    public void logout(String userId, String rawToken, String accessJti, Instant accessExpiry) {
        revocations.revoke(accessJti, accessExpiry);
        // A browser using the session cookie may have no token to hand over. The access token is
        // already revoked above, and that is the part that cannot be delayed.
        if (rawToken == null || rawToken.isBlank()) return;
        mongo.findAndRemove(Query.query(Criteria.where("tokenHash").is(TokenSecrets.hash(rawToken))
                .and("userId").is(userId)), RefreshSession.class);
    }

    public void requestPasswordReset(String email) {
        users.findByEmail(normalizeEmail(email))
                .filter(user -> user.emailVerified() && !user.disabled() && !isDemoUser(user))
                .ifPresent(user -> {
                    String raw = TokenSecrets.generate();
                    resetTokens.save(new PasswordResetToken(user.id(),
                            TokenSecrets.hash(raw), user.id(), Instant.now().plus(RESET_LIFETIME),
                            user.securityVersion()));
                    mongo.remove(Query.query(Criteria.where("userId").is(user.id())
                            .and("_id").ne(user.id())), PasswordResetToken.class);
                    emailSender.sendPasswordReset(user.email(), user.displayName(),
                            publicBaseUrl + "/reset-password.html?token=" + raw);
                });
    }

    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = mongo.findAndRemove(
                Query.query(Criteria.where("tokenHash").is(TokenSecrets.hash(rawToken))),
                PasswordResetToken.class);
        if (token == null || !token.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Reset link is invalid or expired");
        }
        if (!token.id().equals(token.userId()) && resetTokens.findById(token.userId()).isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Reset link is invalid or expired");
        }
        User user = users.findById(token.userId()).orElseThrow(() ->
                new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Reset link is invalid or expired"));
        if (!user.emailVerified() || user.disabled() || isDemoUser(user)
                || !token.isCurrentFor(user)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Reset link is invalid or expired");
        }
        UpdateResult result = mongo.updateFirst(Query.query(Criteria.where("_id").is(user.id())
                        .and("emailVerified").is(true).and("disabled").is(false)
                        .and("securityVersion").is(user.securityVersion())),
                new Update().set("passwordHash", passwords.encode(newPassword))
                        .inc("securityVersion", 1), User.class);
        if (result.getMatchedCount() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Reset link is invalid or expired");
        }
        resetTokens.deleteByUserId(user.id());
        // Even if a concurrent refresh creates a session, its old securityVersion is unusable.
        mongo.remove(Query.query(Criteria.where("userId").is(user.id())), RefreshSession.class);
    }

    AuthResponse issueSession(User user) {
        String rawRefresh = TokenSecrets.generate();
        mongo.insert(new RefreshSession(UUID.randomUUID().toString(), TokenSecrets.hash(rawRefresh),
                user.id(), user.securityVersion(), Instant.now(), Instant.now().plus(REFRESH_LIFETIME)));
        return new AuthResponse(jwt.issueAccessToken(user), rawRefresh, "Bearer",
                jwt.accessLifetimeSeconds());
    }

    public static boolean isDemoUser(User user) {
        return user.id() != null && user.id().startsWith("demo-account-");
    }

    private void issueVerification(User user) {
        String raw = TokenSecrets.generate();
        // A stable ID makes simultaneous resends replace the same record. Only the last saved
        // token can be redeemed. Delete pre-migration random-ID tokens without touching the
        // canonical record, including when another resend replaces it concurrently.
        tokens.save(new EmailVerificationToken(user.id(), TokenSecrets.hash(raw),
                user.id(), Instant.now().plus(VERIFICATION_LIFETIME)));
        mongo.remove(Query.query(Criteria.where("userId").is(user.id())
                .and("_id").ne(user.id())), EmailVerificationToken.class);
        emailSender.sendVerification(user.email(), user.displayName(),
                publicBaseUrl + "/verify-email.html?token=" + raw);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
