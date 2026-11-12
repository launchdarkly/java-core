package com.launchdarkly.sdk.server;

import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class MonotonicTimeTest {
    @Test
    public void timeAfterOverflowIsAfterTimeBeforeOverflow() {
        MonotonicTime beforeOverflow = MonotonicTime.ofNanos(Long.MAX_VALUE);

        // Move one second past the point where the nanosecond count overflows.
        MonotonicTime afterOverflow = beforeOverflow.plus(Duration.ofSeconds(1));

        // The later time compares as later.
        assertTrue(beforeOverflow.isBefore(afterOverflow));
        assertFalse(afterOverflow.isBefore(beforeOverflow));
    }

    @Test
    public void untilMeasuresDurationAcrossOverflow() {
        MonotonicTime beforeOverflow = MonotonicTime.ofNanos(Long.MAX_VALUE);

        // Move one second past the point where the nanosecond count overflows.
        MonotonicTime afterOverflow = beforeOverflow.plus(Duration.ofSeconds(1));

        // The duration is one second forward, and negative one second back.
        assertEquals(Duration.ofSeconds(1), beforeOverflow.until(afterOverflow));
        assertEquals(Duration.ofSeconds(-1), afterOverflow.until(beforeOverflow));
    }
}
