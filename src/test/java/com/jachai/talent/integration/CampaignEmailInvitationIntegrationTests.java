package com.jachai.talent.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class CampaignEmailInvitationIntegrationTests extends BaseMongoIntegrationTest {

    @Test
    void acceptedInvitationRetainsItsRecordForAssignmentRepair() {
        CampaignEmailInvitationRepository invitations = repository();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CampaignEmailInvitation pending = pending(now, now.plusSeconds(60));
        trackDocumentId("campaign_email_invitations", pending.id());
        pending = invitations.insert(pending);

        invitations.save(pending.accepted(now));
        CampaignEmailInvitation persisted = invitations.findById(pending.id()).orElseThrow();

        assertEquals(now, persisted.acceptedAt());
        assertEquals(now.plus(Duration.ofDays(7)), persisted.expiresAt());
        assertNotNull(persisted.revision());
    }

    @Test
    void rotatedTokenCannotAcceptUsingStaleDocumentRevision() {
        CampaignEmailInvitationRepository invitations = repository();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CampaignEmailInvitation original = pending(now, now.plusSeconds(3600));
        trackDocumentId("campaign_email_invitations", original.id());
        original = invitations.insert(original);

        String rotatedHash = generateTestId("rotated-hash");
        invitations.save(original.reissued(rotatedHash, now.plusSeconds(3600)));
        CampaignEmailInvitation stale = original;

        assertThrows(OptimisticLockingFailureException.class,
                () -> invitations.save(stale.accepted(now)));
        CampaignEmailInvitation persisted = invitations.findById(original.id()).orElseThrow();
        assertNull(persisted.acceptedAt());
        assertEquals(rotatedHash, persisted.tokenHash());
    }

    private CampaignEmailInvitationRepository repository() {
        var mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        return new MongoRepositoryFactory(mongo).getRepository(CampaignEmailInvitationRepository.class);
    }

    private CampaignEmailInvitation pending(Instant now, Instant expiresAt) {
        return new CampaignEmailInvitation(generateTestId("invitation"), generateTestId("organization"),
                generateTestId("campaign"), "candidate@example.com", generateTestId("assessment"),
                generateTestId("version"), 1, now.minusSeconds(60), now.plusSeconds(3600),
                generateTestId("token-hash"), now.minusSeconds(60), expiresAt, null, null);
    }
}
