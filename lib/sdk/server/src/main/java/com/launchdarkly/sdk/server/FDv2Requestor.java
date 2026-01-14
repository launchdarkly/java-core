package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import okhttp3.Headers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * Interface for making FDv2 polling requests.
 */
interface FDv2Requestor {
    public static class FDv2PollingResponse {
        private final List<FDv2Event> events;
        private final Headers headers;

        public FDv2PollingResponse(List<FDv2Event> events, Headers headers) {
            this.events = events;
            this.headers = headers;
        }

        public List<FDv2Event> getEvents() {
            return events;
        }

        public Headers getHeaders() {
            return headers;
        }
    }
    CompletableFuture<FDv2PollingResponse> Poll(Selector selector);

    void close();
}
