package com.launchdarkly.sdk.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reads the TTL of an FDv1 fallback directive, which tells the SDK how long to stop using FDv2 before attempting to
 * recover to it. The directive is carried by the {@code X-LD-FD-Fallback} response header, with its TTL in the
 * {@code X-LD-FD-Fallback-TTL} header, or by the {@code protocolFallbackTTL} field of a {@code goodbye} event.
 */
final class FDv1FallbackDirective {
    /**
     * The maximum TTL, which is also the TTL used when the service supplies no usable TTL.
     */
    static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private static final String PROTOCOL_FALLBACK_TTL = "protocolFallbackTTL";

    private FDv1FallbackDirective() {
    }

    /**
     * Returns a service-supplied TTL as given if it is in the range (0, {@link #DEFAULT_TTL}]. Any other value,
     * including null, is replaced by {@link #DEFAULT_TTL} minus a jitter of up to half of it, so that a fallback is
     * never indefinite and SDKs that fell back together do not all attempt to recover at once.
     *
     * @param ttl the TTL supplied by the service, or null if none was supplied
     * @return the TTL to use
     */
    static Duration ttlOrDefault(Duration ttl) {
        if (ttl != null && !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(DEFAULT_TTL) <= 0) {
            return ttl;
        }
        long ttlSeconds = DEFAULT_TTL.getSeconds();
        long jitterSeconds = ThreadLocalRandom.current().nextLong(ttlSeconds / 2 + 1);
        return Duration.ofSeconds(ttlSeconds - jitterSeconds);
    }

    /**
     * Reads a directive from the {@code X-LD-FD-Fallback} and {@code X-LD-FD-Fallback-TTL} response headers. The TTL
     * header is a whole number of seconds.
     *
     * @param fallbackHeader the value of the {@code X-LD-FD-Fallback} header, or null
     * @param ttlHeader the value of the {@code X-LD-FD-Fallback-TTL} header, or null
     * @return the TTL to use, or null if the headers do not request fallback
     */
    static Duration fromHeaders(String fallbackHeader, String ttlHeader) {
        if (fallbackHeader == null || !fallbackHeader.equalsIgnoreCase("true")) {
            return null;
        }
        if (ttlHeader == null) {
            return ttlOrDefault(null);
        }
        try {
            return ttlOrDefault(Duration.ofSeconds(Long.parseLong(ttlHeader.trim())));
        } catch (NumberFormatException e) {
            return ttlOrDefault(null);
        }
    }

    /**
     * Reads a directive from a {@code goodbye} event. The presence of {@code protocolFallbackTTL} is itself the
     * directive; its value is the TTL in seconds.
     *
     * @param goodbye the goodbye event
     * @return the TTL to use, or null if the event has no {@code protocolFallbackTTL}
     */
    static Duration fromGoodbye(FDv2Event goodbye) {
        JsonElement data = goodbye.getData();
        if (data == null || !data.isJsonObject()) {
            return null;
        }
        JsonObject object = data.getAsJsonObject();
        if (!object.has(PROTOCOL_FALLBACK_TTL) || object.get(PROTOCOL_FALLBACK_TTL).isJsonNull()) {
            return null;
        }
        JsonElement ttl = object.get(PROTOCOL_FALLBACK_TTL);
        if (!ttl.isJsonPrimitive() || !ttl.getAsJsonPrimitive().isNumber()) {
            return ttlOrDefault(null);
        }
        return ttlOrDefault(Duration.ofSeconds((long) ttl.getAsDouble()));
    }
}
