package com.jachai.auth.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.organization.repository.MembershipRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class UnverifiedAccountCleanup {
    private static final Logger log = LoggerFactory.getLogger(UnverifiedAccountCleanup.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int MAX_BATCH = 100;

    private final MongoTemplate mongo;
    private final MembershipRepository memberships;
    private final EmailVerificationTokenRepository tokens;

    public UnverifiedAccountCleanup(MongoTemplate mongo, MembershipRepository memberships,
                                    EmailVerificationTokenRepository tokens) {
        this.mongo = mongo;
        this.memberships = memberships;
        this.tokens = tokens;
    }

    @Scheduled(initialDelayString = "${app.auth.unverified-cleanup-initial-ms:60000}",
            fixedDelayString = "${app.auth.unverified-cleanup-ms:3600000}")
    public void removeExpiredAccounts() {
        Instant cutoff = Instant.now().minus(RETENTION);
        Query candidates = Query.query(Criteria.where("emailVerified").is(false)
                        .and("createdAt").lt(cutoff))
                .with(Sort.by(Sort.Direction.ASC, "createdAt")).limit(MAX_BATCH);
        int removed = 0;
        for (User user : mongo.find(candidates, User.class)) {
            if (memberships.existsByUserId(user.id())) continue;
            Query stillUnverified = Query.query(Criteria.where("_id").is(user.id())
                    .and("emailVerified").is(false).and("createdAt").lt(cutoff));
            if (mongo.remove(stillUnverified, User.class).getDeletedCount() == 1) {
                tokens.deleteByUserId(user.id());
                removed++;
            }
        }
        if (removed > 0) log.info("Removed {} expired unverified accounts", removed);
    }
}
