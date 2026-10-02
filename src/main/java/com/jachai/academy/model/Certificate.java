package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("certificates")
@CompoundIndexes({
        @CompoundIndex(name = "certificate_org_attempt_unique", def = "{'organizationId': 1, 'attemptId': 1}", unique = true),
        @CompoundIndex(name = "certificate_org_learner", def = "{'organizationId': 1, 'learnerUserId': 1}"),
        @CompoundIndex(name = "certificate_org_learner_issued", def = "{'organizationId': 1, 'learnerUserId': 1, 'issuedAt': -1, '_id': -1}")
})
public record Certificate(
        @Id String id,
        String organizationId,
        String learnerUserId,
        String attemptId,
        String assessmentVersionId,
        @Indexed(unique = true) String verificationCode,
        CertificateStatus status,
        Instant issuedAt,
        Instant revokedAt,
        String issuedByUserId,
        String revokedByUserId,
        @Version Long revision) {

    public Certificate(String id, String organizationId, String learnerUserId, String attemptId,
                       String assessmentVersionId, String verificationCode, CertificateStatus status,
                       Instant issuedAt, Instant revokedAt, Long revision) {
        this(id, organizationId, learnerUserId, attemptId, assessmentVersionId, verificationCode,
                status, issuedAt, revokedAt, null, null, revision);
    }

    public Certificate revoke(Instant when, String actorUserId) {
        return new Certificate(id, organizationId, learnerUserId, attemptId, assessmentVersionId,
                verificationCode, CertificateStatus.REVOKED, issuedAt, when,
                issuedByUserId, actorUserId, revision);
    }

    public Certificate revoke(Instant when) {
        return revoke(when, null);
    }
}
