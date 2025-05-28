package com.launchdarkly.sdk.server.ai.tracking;

/**
 * Represents information returned by a model provider.
 */
public final class Response {
    private final Usage usage;
    private final Metrics metrics;

    public Response(Usage usage, Metrics metrics) {
        this.usage = usage;
        this.metrics = metrics;
    }

    public Usage getUsage() {
        return usage;
    }

    public Metrics getMetrics() {
        return metrics;
    }
}
