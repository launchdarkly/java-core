package com.launchdarkly.sdk.server.ai.tracking;

/**
 * Represents metrics returned by a model provider.
 */
public final class Metrics {
    private final Long latencyMs;

    public Metrics(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }
}
