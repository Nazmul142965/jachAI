package com.jachai.academy.repository;

import com.jachai.academy.model.Course;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CourseRepository extends MongoRepository<Course, String> {
    Optional<Course> findByIdAndOrganizationId(String id, String organizationId);
    Page<Course> findByOrganizationId(String organizationId, Pageable pageable);
}
