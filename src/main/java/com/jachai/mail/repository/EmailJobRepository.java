package com.jachai.mail.repository;

import com.jachai.mail.model.EmailJob;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailJobRepository extends MongoRepository<EmailJob, String> { }
