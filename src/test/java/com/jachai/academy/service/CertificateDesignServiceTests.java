package com.jachai.academy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.academy.dto.UpdateCertificateDesignRequest;
import com.jachai.academy.model.ActiveCertificateDesign;
import com.jachai.academy.model.CertificateDesign;
import com.jachai.academy.repository.ActiveCertificateDesignRepository;
import com.jachai.academy.repository.CertificateDesignRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CertificateDesignServiceTests {
    @Mock CertificateDesignRepository designs;
    @Mock ActiveCertificateDesignRepository active;
    @Mock OrganizationRepository organizations;
    @Mock TenantAccessService access;

    private CertificateDesignService service() {
        return new CertificateDesignService(designs, active, organizations, access);
    }

    private void academy() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(
                new Organization("org-a", "Academy", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    @Test
    void savesAnImmutableVersionAndSelectsItForFutureIssuance() {
        academy();
        when(designs.insert(any(CertificateDesign.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(active.save(any(ActiveCertificateDesign.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service().update("owner", "org-a", new UpdateCertificateDesignRequest(
                "  Achievement  ", "  Completed Java  ", "Dr Example", null, null));

        ArgumentCaptor<CertificateDesign> version = ArgumentCaptor.forClass(CertificateDesign.class);
        verify(designs).insert(version.capture());
        assertEquals("org-a", version.getValue().organizationId());
        assertEquals("Achievement", version.getValue().heading());
        assertEquals(version.getValue().id(), saved.id());
        verify(active).save(new ActiveCertificateDesign("org-a", saved.id(), null));
        verify(access).requireRole("owner", "org-a", OrganizationRole.ORG_ADMIN);
    }

    @Test
    void rejectsUntrustedImageDataBeforeWritingAnything() {
        academy();
        ApiException error = assertThrows(ApiException.class, () -> service().update("owner", "org-a",
                new UpdateCertificateDesignRequest("Heading", "Message", "Signer",
                        "data:image/svg+xml;base64,PHN2Zz4=", null)));
        assertEquals("INVALID_CERTIFICATE_IMAGE", error.code());
        verify(designs, never()).insert(any(CertificateDesign.class));
    }

    @Test
    void limitsDesignVersionsToProtectSmallAtlasDatabases() {
        academy();
        when(designs.countByOrganizationIdAndCreatedAtAfter(eq("org-a"), any(Instant.class)))
                .thenReturn(10L);

        ApiException error = assertThrows(ApiException.class, () -> service().update("owner", "org-a",
                new UpdateCertificateDesignRequest("Heading", "Message", "Signer", null, null)));

        assertEquals("CERTIFICATE_DESIGN_LIMIT", error.code());
        verify(designs, never()).insert(any(CertificateDesign.class));
    }

    @Test
    void resolvesHistoricalDesignOnlyWithinTheCertificateTenant() {
        CertificateDesign design = new CertificateDesign("d1", "org-a", "Heading", "Message",
                "Signer", null, null, Instant.now(), "owner");
        when(designs.findByIdAndOrganizationId("d1", "org-a")).thenReturn(Optional.of(design));

        assertNotNull(service().byId("org-a", "d1"));
        verify(designs).findByIdAndOrganizationId("d1", "org-a");
        verify(designs, never()).findByIdAndOrganizationId(eq("d1"), eq("org-b"));
    }
}
