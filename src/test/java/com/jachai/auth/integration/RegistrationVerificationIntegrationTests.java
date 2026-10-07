package com.jachai.auth.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.AuthService;
import com.jachai.auth.service.EmailSender;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.shared.error.ApiException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class RegistrationVerificationIntegrationTests extends BaseMongoIntegrationTest {
    @Autowired private AuthService auth;
    @Autowired private UserRepository users;
    @Autowired private MongoTemplate mongo;
    @MockitoBean private EmailSender emailSender;

    @Test
    void registeredUsersCanVerifyTheEmailLinkPersistedInMongo() {
        String email = generateTestId("learner") + "@example.test";
        auth.register(new RegisterRequest(email, "Academy Learner", "LocalSmoke-Password-123!"));
        String userId = users.findByEmail(email).orElseThrow().id();
        trackDocumentId("users", userId);
        EmailVerificationToken saved = mongo.findOne(
                Query.query(Criteria.where("userId").is(userId)), EmailVerificationToken.class);
        if (saved == null) throw new AssertionError("Registration did not persist its verification token");
        trackDocumentId("email_verification_tokens", saved.id());

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendVerification(eq(email), eq("Academy Learner"), url.capture());
        String query = URI.create(url.getValue()).getRawQuery();
        if (query == null || !query.startsWith("token=")) {
            throw new AssertionError("Verification email did not contain a token");
        }

        auth.verifyEmail(query.substring("token=".length()));

        assertTrue(users.findById(userId).orElseThrow().emailVerified());
    }

    @Test
    void resendsReplaceTheMongoTokenAndOnlyTheLatestLinkVerifies() {
        String email = generateTestId("resend") + "@example.test";
        auth.register(new RegisterRequest(email, "Resend Learner", "LocalSmoke-Password-123!"));
        String userId = users.findByEmail(email).orElseThrow().id();
        trackDocumentId("users", userId);
        trackDocumentId("email_verification_tokens", userId);

        auth.resendVerification(email);
        auth.resendVerification(email);

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(emailSender, times(3)).sendVerification(eq(email), eq("Resend Learner"), urls.capture());
        List<String> links = urls.getAllValues();
        assertEquals(1, mongo.count(Query.query(Criteria.where("userId").is(userId)),
                EmailVerificationToken.class));
        assertEquals(userId, mongo.findOne(Query.query(Criteria.where("userId").is(userId)),
                EmailVerificationToken.class).id());
        for (int i = 0; i < 2; i++) {
            String oldToken = URI.create(links.get(i)).getRawQuery().substring("token=".length());
            assertEquals("INVALID_TOKEN", assertThrows(ApiException.class,
                    () -> auth.verifyEmail(oldToken)).code());
        }
        String latestToken = URI.create(links.get(2)).getRawQuery().substring("token=".length());
        auth.verifyEmail(latestToken);
        assertTrue(users.findById(userId).orElseThrow().emailVerified());
    }
}
