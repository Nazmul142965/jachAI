package com.jachai.billing.repository;

import com.jachai.billing.model.PaymentOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentOrderRepository extends MongoRepository<PaymentOrder, String> {
    Page<PaymentOrder> findByOrganizationId(String organizationId, Pageable pageable);
    Optional<PaymentOrder> findByIdAndOrganizationId(String id, String organizationId);
}
