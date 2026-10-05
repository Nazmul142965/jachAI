package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("certificate_designs")
@CompoundIndex(name = "certificate_design_org_created", def = "{'organizationId': 1, 'createdAt': -1}")
public record CertificateDesign(@Id String id, String organizationId, String heading,
                                String message, String signatoryName, String logoDataUrl,
                                String signatureDataUrl, Instant createdAt, String createdByUserId) { }
