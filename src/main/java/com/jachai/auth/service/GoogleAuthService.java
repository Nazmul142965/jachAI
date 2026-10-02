package com.jachai.auth.service;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {
    private final GoogleIdTokenVerifier google;
    private final UserRepository users;
    private final MongoTemplate mongo;
    private final AuthService auth;

    public String publicClientId() {
        return google.clientId();
    }

    public AuthResponse login(String credential) {
        var profile = google.verify(credential);
        User linked = users.findByGoogleSubject(profile.subject()).orElse(null);
        if (linked != null) {
            if (linked.disabled() || !linked.emailVerified()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "Account is unavailable");
            }
            return auth.issueSession(linked);
        }
        if (!profile.authoritativeEmail()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED",
                    "Use email registration and verification, then link this Google account");
        }
        if (users.findByEmail(profile.email()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED",
                    "Sign in with your password, then link Google from your account");
        }
        User newUser = new User(UUID.randomUUID().toString(), profile.email(), profile.displayName(),
                null, true, false, 0, Instant.now(), profile.subject());
        try {
            return auth.issueSession(users.insert(newUser));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED",
                    "This email or Google account is already registered; sign in and link it");
        }
    }

    public void link(String actorId, String credential) {
        var profile = google.verify(credential);
        User user = users.findById(actorId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "Account is unavailable"));
        if (user.disabled() || !user.emailVerified() || !user.email().equals(profile.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_EMAIL_MISMATCH",
                    "Google account email must match your verified JachAI email");
        }
        if (profile.subject().equals(user.googleSubject())) return;
        if (user.googleSubject() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_ALREADY_LINKED",
                    "A different Google account is already linked");
        }
        try {
            var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(actorId)
                            .and("googleSubject").is(null).and("disabled").is(false)
                            .and("emailVerified").is(true).and("email").is(profile.email())),
                    new Update().set("googleSubject", profile.subject()), User.class);
            if (result.getModifiedCount() != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_LINK_CONFLICT",
                        "Account changed while linking Google; reload and try again");
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_ALREADY_LINKED",
                    "This Google account is already linked to another user");
        }
    }
}
