package com.launchdarkly.sdk.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.server.datasources.FDv1FallbackDirective;

import java.time.Duration;

/**
 * Reads FDv1 fallback directives from the signals that can carry them.
 */
final class FDv1FallbackDirectives {
    private static final String PROTOCOL_FALLBACK_TTL = "protocolFallbackTTL";

    private FDv1FallbackDirectives() {
    }

    /**
     * Reads a directive from the {@code X-LD-FD-Fallback} and {@code X-LD-FD-Fallback-TTL} response headers.
     *
     * @param fallbackHeader the value of the {@code X-LD-FD-Fallback} header, or null
     * @param ttlHeader the value of the {@code X-LD-FD-Fallback-TTL} header, or null
     * @return the directive, or null if the headers do not request fallback
     */
    static FDv1FallbackDirective fromHeaders(String fallbackHeader, String ttlHeader) {
        if (fallbackHeader == null || !fallbackHeader.equalsIgnoreCase("true")) {
            return null;
        }
        return FDv1FallbackDirective.fromServiceTtl(ttlHeader);
    }

    /**
     * Reads a directive from a {@code goodbye} event. The presence of {@code protocolFallbackTTL} is itself the
     * directive; its value is the TTL in seconds.
     *
     * @param goodbye the goodbye event
     * @return the directive, or null if the event has no {@code protocolFallbackTTL}
     */
    static FDv1FallbackDirective fromGoodbye(FDv2Event goodbye) {
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
            return FDv1FallbackDirective.defaultTtl();
        }
        return FDv1FallbackDirective.fromServiceTtl(Duration.ofSeconds((long) ttl.getAsDouble()));
    }
}
