package com.jachai.billing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.dto.UsageReconciliationEntry;
import com.jachai.billing.dto.UsageReconciliationReport;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageReconciliationStatus;
import com.jachai.billing.service.EntitlementService;
import com.jachai.billing.service.EntitlementUsageReconciliationService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class EntitlementControllerTests {
    @Mock EntitlementService entitlements;
    @Mock EntitlementUsageReconciliationService reconciliation;

    private EntitlementController controller() {
        return new EntitlementController(entitlements, reconciliation);
    }

    private static Jwt actor(String subject) {
        return new Jwt("token", Instant.now().minusSeconds(10), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", subject));
    }

    private static UsageReconciliationReport report(String organizationId) {
        return new UsageReconciliationReport(organizationId, Instant.parse("2026-10-20T12:00:00Z"),
                "FREE", 300, 15, false,
                List.of(new UsageReconciliationEntry(UsageMetric.CERTIFICATE, "2026-10",
                        organizationId + ":CERTIFICATE:2026-10", true, 4, 1, 1, 30, false,
                        UsageReconciliationStatus.STRANDED, null, "detail")),
                List.of("follow up"));
    }

    @Test
    void reportEndpointReachesOnlyTheTenantInThePath() {
        when(reconciliation.report("admin-a", "org-a")).thenReturn(report("org-a"));

        UsageReconciliationReport result = controller().report(actor("admin-a"), "org-a");

        assertEquals("org-a", result.organizationId());
        verify(reconciliation).report("admin-a", "org-a");
        verify(reconciliation, never()).repair("admin-a", "org-a");
    }

    @Test
    void repairEndpointReachesOnlyTheTenantInThePath() {
        when(reconciliation.repair("admin-a", "org-a")).thenReturn(report("org-a"));

        UsageReconciliationReport result = controller().repair(actor("admin-a"), "org-a");

        assertEquals(List.of("follow up"), result.manualSteps());
        verify(reconciliation).repair("admin-a", "org-a");
        verify(reconciliation, never()).report("admin-a", "org-a");
    }

    @Test
    void anotherTenantsAdministratorIsRejectedByTheService() {
        when(reconciliation.report("admin-b", "org-b"))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient organization permission"));

        ApiException failure = assertThrows(ApiException.class,
                () -> controller().report(actor("admin-b"), "org-b"));

        assertEquals(HttpStatus.FORBIDDEN, failure.status());
        verify(reconciliation, never()).report("admin-b", "org-a");
    }
}
