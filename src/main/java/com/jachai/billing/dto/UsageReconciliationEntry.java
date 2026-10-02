package com.jachai.billing.dto;

import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageReconciliationStatus;

public record UsageReconciliationEntry(UsageMetric metric, String window, String bucketId,
                                       boolean bucketExists, long recordedUsage,
                                       long provenMinimumUsage, long provenMaximumUsage,
                                       int planLimit, boolean windowClosed,
                                       UsageReconciliationStatus status, Long appliedUsage,
                                       String detail) {

    public long usageAfterRepair() {
        return appliedUsage == null ? recordedUsage : appliedUsage;
    }

    public long projectedUsage() {
        return status == UsageReconciliationStatus.BACKFILL_REQUIRED
                ? provenMinimumUsage() : usageAfterRepair();
    }

    public boolean exceedsPlanLimit() {
        return projectedUsage() > planLimit;
    }
}
