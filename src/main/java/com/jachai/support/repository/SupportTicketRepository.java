package com.jachai.support.repository;

import com.jachai.support.type.TicketStatus;
import com.jachai.support.model.SupportTicket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

public interface SupportTicketRepository extends MongoRepository<SupportTicket, String> {
    Page<SupportTicket> findByOwnerUserId(String ownerUserId, Pageable pageable);

    Page<SupportTicket> findByStatus(TicketStatus status, Pageable pageable);

    Page<SupportTicket> findByOrganizationId(String organizationId, Pageable pageable);

    Page<SupportTicket> findByStatusAndOrganizationId(TicketStatus status, String organizationId, Pageable pageable);

    Page<SupportTicket> findByStatusInAndOrganizationId(List<TicketStatus> statuses, String organizationId, Pageable pageable);

    long countByStatus(TicketStatus status);

    long countByStatusIn(List<TicketStatus> statuses);

    long countByStatusInAndOrganizationId(List<TicketStatus> statuses, String organizationId);

    long countByStatusNot(TicketStatus status);

    long countByStatusNotInAndOrganizationId(List<TicketStatus> statuses, String organizationId);

    long countByOrganizationId(String organizationId);

    @Query("{ 'status': { \$in: ?0 }, 'organizationId': ?1, 'createdAt': { \$gte: ?2, \$lt: ?3 } }")
    Page<SupportTicket> findByStatusInAndOrganizationIdAndCreatedAtBetween(
            List<TicketStatus> statuses, String organizationId, Instant from, Instant to, Pageable pageable);

    Page<SupportTicket> findByStatusNotIn(List<TicketStatus> statuses, Pageable pageable);
}