package com.jachai.billing.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

public record UsageWindow(UsageMetric metric, String window, String bucketId, Instant expiresAt,
                          YearMonth month) {

    public static UsageWindow of(String organizationId, UsageMetric metric, Instant at) {
        return metric == UsageMetric.PUBLISHED_ASSESSMENT
                ? lifetime(organizationId, metric)
                : ofMonth(organizationId, metric, YearMonth.from(LocalDate.ofInstant(at, ZoneOffset.UTC)));
    }

    public static UsageWindow ofMonth(String organizationId, UsageMetric metric, YearMonth month) {
        if (metric == UsageMetric.PUBLISHED_ASSESSMENT) {
            return lifetime(organizationId, metric);
        }
        String window = month.toString();
        return new UsageWindow(metric, window, bucketId(organizationId, metric, window),
                month.atDay(1).plusMonths(14).atStartOfDay().toInstant(ZoneOffset.UTC), month);
    }

    public boolean monthly() {
        return month != null;
    }

    public boolean closedAt(Instant at) {
        return month != null && !end().isAfter(at);
    }

    private Instant end() {
        return month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static UsageWindow lifetime(String organizationId, UsageMetric metric) {
        return new UsageWindow(metric, "all", bucketId(organizationId, metric, "all"), null, null);
    }

    private static String bucketId(String organizationId, UsageMetric metric, String window) {
        return organizationId + ":" + metric.name() + ":" + window;
    }
}
