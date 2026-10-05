package com.jachai.academy.service;

import com.jachai.academy.dto.CertificateDesignView;
import com.jachai.academy.dto.UpdateCertificateDesignRequest;
import com.jachai.academy.model.ActiveCertificateDesign;
import com.jachai.academy.model.CertificateDesign;
import com.jachai.academy.repository.ActiveCertificateDesignRepository;
import com.jachai.academy.repository.CertificateDesignRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CertificateDesignService {
    private static final int MAX_IMAGE_BYTES = 64 * 1024;
    private static final int MAX_IMAGE_SIDE = 1200;
    private static final int MAX_VERSIONS_PER_30_DAYS = 10;
    private final CertificateDesignRepository designs;
    private final ActiveCertificateDesignRepository active;
    private final OrganizationRepository organizations;
    private final TenantAccessService access;

    public CertificateDesignView current(String actorId, String organizationId) {
        requireAcademyAdmin(actorId, organizationId);
        CertificateDesign design = currentDesign(organizationId);
        return design == null ? new CertificateDesignView(null, "Certificate of achievement",
                "Has successfully completed the assessment", "", null, null, null)
                : CertificateDesignView.from(design);
    }

    public CertificateDesignView update(String actorId, String organizationId,
                                        UpdateCertificateDesignRequest request) {
        requireAcademyAdmin(actorId, organizationId);
        String logo = checkedImage(request.logoDataUrl());
        String signature = checkedImage(request.signatureDataUrl());
        if (designs.countByOrganizationIdAndCreatedAtAfter(organizationId,
                Instant.now().minusSeconds(30L * 86400)) >= MAX_VERSIONS_PER_30_DAYS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_DESIGN_LIMIT",
                    "Ten certificate design saves are allowed per workspace in 30 days; preview before saving");
        }
        ActiveCertificateDesign previous = active.findById(organizationId).orElse(null);
        CertificateDesign design = designs.insert(new CertificateDesign(UUID.randomUUID().toString(),
                organizationId, request.heading().trim(), request.message().trim(),
                request.signatoryName() == null ? "" : request.signatoryName().trim(),
                logo, signature, Instant.now(), actorId));
        try {
            active.save(new ActiveCertificateDesign(organizationId, design.id(),
                    previous == null ? null : previous.revision()));
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_DESIGN_CONFLICT",
                    "Certificate design changed concurrently; reload and retry");
        }
        return CertificateDesignView.from(design);
    }

    public String activeId(String organizationId) {
        return active.findById(organizationId).map(ActiveCertificateDesign::designId).orElse(null);
    }

    public CertificateDesignView byId(String organizationId, String designId) {
        if (designId == null) return null;
        return designs.findByIdAndOrganizationId(designId, organizationId)
                .map(CertificateDesignView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_DESIGN_MISSING",
                        "Certificate design snapshot is unavailable"));
    }

    private CertificateDesign currentDesign(String organizationId) {
        String id = activeId(organizationId);
        if (id == null) return null;
        return designs.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_DESIGN_MISSING",
                        "Current certificate design is unavailable"));
    }

    private void requireAcademyAdmin(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        if (organizations.findById(organizationId)
                .filter(org -> org.type() == OrganizationType.ACADEMY).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY",
                    "Certificate design is available to Academy organizations");
        }
    }

    private static String checkedImage(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        String prefix;
        if (dataUrl.startsWith("data:image/png;base64,")) prefix = "png";
        else if (dataUrl.startsWith("data:image/jpeg;base64,")) prefix = "jpeg";
        else throw invalidImage();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        } catch (IllegalArgumentException exception) {
            throw invalidImage();
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw invalidImage();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(format.equals(prefix) || prefix.equals("jpeg") && format.equals("jpg"))
                        || reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                        || reader.getWidth(0) > MAX_IMAGE_SIDE || reader.getHeight(0) > MAX_IMAGE_SIDE) {
                    throw invalidImage();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw invalidImage();
        }
        return dataUrl;
    }

    private static ApiException invalidImage() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CERTIFICATE_IMAGE",
                "Use a PNG or JPEG image up to 64 KiB and 1200 pixels per side");
    }
}
