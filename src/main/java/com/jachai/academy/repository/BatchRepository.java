package com.jachai.academy.repository;

import com.jachai.academy.model.Batch;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BatchRepository extends MongoRepository<Batch, String> {
    Optional<Batch> findByIdAndOrganizationIdAndCourseId(String id, String organizationId, String courseId);
    Optional<Batch> findByIdAndOrganizationId(String id, String organizationId);
    Page<Batch> findByOrganizationIdAndCourseId(String organizationId, String courseId, Pageable pageable);
}
