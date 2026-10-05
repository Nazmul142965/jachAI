package com.jachai.academy.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("certificate_design_active")
public record ActiveCertificateDesign(@Id String organizationId, String designId,
                                      @Version Long revision) { }
