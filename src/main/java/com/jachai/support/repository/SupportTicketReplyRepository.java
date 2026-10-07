package com.jachai.support.repository;

import com.jachai.support.model.SupportTicketReply;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

public interface SupportTicketReplyRepository extends MongoRepository<SupportTicketReply, String> {
    Page<SupportTicketReply> findByTicketId(String ticketId, Pageable pageable);

    Page<SupportTicketReply> findByTicketIdAndPublicReplyTrue(String ticketId, Pageable pageable);

    Page<SupportTicketReply> findByTicketIdAndPublicReplyFalse(String ticketId, Pageable pageable);

    long countByTicketId(String ticketId);

    long countByTicketIdAndPublicReplyTrue(String ticketId);

    long countByTicketIdAndPublicReplyFalse(String ticketId);

    long countByTicketIdAndCreatedAtAfter(Instant cutoff);

    @Query("{ 'ticketId': ?0, 'createdAt': { $gte: ?1, $lt: ?2 } }")
    Page<SupportTicketReply> findByTicketIdAndCreatedAtBetween(String ticketId, Instant from, Instant to, Pageable pageable);

    @Query("{ 'ticketId': ?0, 'createdAt': { $gte: ?1 } }")
    List<SupportTicketReply> findByTicketIdAndCreatedAtAfter(String ticketId, Instant cutoff);
}