package com.jachai.platform.repository;

import com.jachai.platform.model.PlatformRoleEvent;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformRoleEventRepository extends MongoRepository<PlatformRoleEvent, String> {
    Optional<PlatformRoleEvent> findFirstByTargetUserIdOrderByRevisionDesc(String targetUserId);
    Page<PlatformRoleEvent> findByTargetUserId(String targetUserId, Pageable pageable);
}
