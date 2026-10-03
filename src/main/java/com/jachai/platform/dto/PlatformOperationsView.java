package com.jachai.platform.dto;

import java.time.Instant;

public record PlatformOperationsView(Instant generatedAt, long failedEmailJobs,
                                     long overdueEmailJobs, long oldOpenSandboxOrders,
                                     long verifiedButUnappliedOrders,
                                     long usageRepairRunsWithoutCompletion) { }
