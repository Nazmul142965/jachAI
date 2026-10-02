package com.jachai.billing.dto;

import com.jachai.billing.model.UsageMetric;
import java.time.Instant;
import java.util.List;

public record UsageReconciliationReport(String organizationId, Instant generatedAt, String tier,
                                        int downwardGraceSeconds, int lookbackMonths,
                                        boolean repaired, List<UsageReconciliationEntry> entries,
                                        List<String> manualSteps) {

    public boolean reconciled() {
        return manualSteps.isEmpty();
    }

    public UsageReconciliationEntry entry(UsageMetric metric, String window) {
        return entries.stream()
                .filter(candidate -> candidate.metric() == metric && candidate.window().equals(window))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No reconciliation entry for " + metric + " " + window));
    }
}
