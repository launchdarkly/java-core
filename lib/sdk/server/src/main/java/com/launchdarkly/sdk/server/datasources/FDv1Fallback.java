package com.launchdarkly.sdk.server.datasources;

import java.time.Duration;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * A server directive to fall back to FDv1.
 */
public final class FDv1Fallback {
    private final Duration ttl;

    /**
     * Creates a fallback directive with the server-provided TTL.
     *
     * @param ttl the TTL, or null if the server gave none
     */
    public FDv1Fallback(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Returns the server-provided TTL.
     *
     * @return the TTL, or null if the server gave none
     */
    public Duration getTtl() {
        return ttl;
    }
}
