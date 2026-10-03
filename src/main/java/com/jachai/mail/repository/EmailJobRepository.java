package com.jachai.mail.repository;

import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import java.time.Instant;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailJobRepository extends MongoRepository<EmailJob, String> {
    long countByStatus(EmailJobStatus status);
    long countByStatusAndNextAttemptAtBefore(EmailJobStatus status, Instant before);
}
