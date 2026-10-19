package com.launchdarkly.sdk.server.datasources;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * An FDv1 fallback directive received from LaunchDarkly, either through the {@code X-LD-FD-Fallback} response
 * header or through the {@code protocolFallbackTTL} field of a {@code goodbye} event. The directive tells the SDK to
 * stop using FDv2 for the duration of its TTL, after which the SDK attempts to recover to FDv2.
 */
public final class FDv1FallbackDirective {
    /**
     * The maximum TTL, which is also the TTL used when the service supplies no usable TTL.
     */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final Duration ttl;

    private FDv1FallbackDirective(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Builds a directive using the default TTL with jitter applied, so that SDKs which fell back together do not
     * all attempt to recover at once. The resulting TTL is between half of {@link #DEFAULT_TTL} and
     * {@link #DEFAULT_TTL}.
     *
     * @return a directive with a jittered default TTL
     */
    public static FDv1FallbackDirective defaultTtl() {
        long ttlSeconds = DEFAULT_TTL.getSeconds();
        long jitterSeconds = ThreadLocalRandom.current().nextLong(ttlSeconds / 2 + 1);
        return new FDv1FallbackDirective(Duration.ofSeconds(ttlSeconds - jitterSeconds));
    }

    /**
     * Builds a directive from a TTL supplied by the service. A TTL in the range (0, {@link #DEFAULT_TTL}] is used
     * as given, without jitter. Any other value, including null, uses {@link #defaultTtl()} instead, so a fallback
     * is never indefinite.
     *
     * @param ttl the TTL supplied by the service, or null if none was supplied
     * @return the directive
     */
    public static FDv1FallbackDirective fromServiceTtl(Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(DEFAULT_TTL) <= 0) {
            return new FDv1FallbackDirective(ttl);
        }
        return defaultTtl();
    }

    /**
     * Builds a directive from the raw value of an {@code X-LD-FD-Fallback-TTL} header, which is a whole number of
     * seconds. A missing or malformed value uses {@link #defaultTtl()}.
     *
     * @param ttlSeconds the header value, or null if the header was not present
     * @return the directive
     */
    public static FDv1FallbackDirective fromServiceTtl(String ttlSeconds) {
        if (ttlSeconds == null) {
            return defaultTtl();
        }
        try {
            return fromServiceTtl(Duration.ofSeconds(Long.parseLong(ttlSeconds.trim())));
        } catch (NumberFormatException e) {
            return defaultTtl();
        }
    }

    /**
     * Returns how long the SDK should stay off FDv2 before attempting to recover to it.
     *
     * @return the TTL
     */
    public Duration getTtl() {
        return ttl;
    }
}
