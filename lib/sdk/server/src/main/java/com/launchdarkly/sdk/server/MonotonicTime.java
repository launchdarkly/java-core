package com.launchdarkly.sdk.server;

import java.time.Duration;

/**
 * A point in time on the monotonic clock of {@link System#nanoTime()}. Changes to the system clock do not affect it.
 * The value has no meaning as a date, so it is only useful to compare with other {@code MonotonicTime} values.
 */
final class MonotonicTime {
    private final long nanos;

    private MonotonicTime(long nanos) {
        this.nanos = nanos;
    }

    /**
     * The current time.
     *
     * @return the current time
     */
    static MonotonicTime now() {
        return new MonotonicTime(System.nanoTime());
    }

    /**
     * A time with the given value of {@link System#nanoTime()}. Visible for testing.
     *
     * @param nanos the value of {@link System#nanoTime()}
     * @return the time
     */
    static MonotonicTime ofNanos(long nanos) {
        return new MonotonicTime(nanos);
    }

    /**
     * The time after the given duration.
     *
     * @param duration the duration to add
     * @return the later time
     */
    MonotonicTime plus(Duration duration) {
        return new MonotonicTime(nanos + duration.toNanos());
    }

    /**
     * True if this time is before the other time.
     *
     * @param other the other time
     * @return true if this time is before the other time
     */
    boolean isBefore(MonotonicTime other) {
        // The nanosecond count can overflow, so compare the difference instead of the counts.
        return nanos - other.nanos < 0;
    }

    /**
     * The duration from this time until the other time. It is negative if the other time is before this time.
     *
     * @param other the other time
     * @return the duration until the other time
     */
    Duration until(MonotonicTime other) {
        return Duration.ofNanos(other.nanos - nanos);
    }
}
