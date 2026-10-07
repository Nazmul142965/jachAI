package com.jachai.practice.service;

/**
 * Stated limits and retention durations for personal practice.
 *
 * <p>These constants back the user-visible daily free allowance and the
 * documented retention policy. Generated questions belong only to the
 * requesting user, expire after {@link #GENERATION_RETENTION_DAYS} days,
 * and never affect official assessment scores. Answer history is user-only
 * and expires after {@link #ANSWER_RETENTION_DAYS} days.
 */
public final class PracticeLimits {
    public static final int DAILY_FREE_ANSWERS = 20;
    public static final int DAILY_FREE_GENERATIONS = 20;
    public static final int GENERATION_RETENTION_DAYS = 30;
    public static final int ANSWER_RETENTION_DAYS = 90;

    private PracticeLimits() {
    }
}
