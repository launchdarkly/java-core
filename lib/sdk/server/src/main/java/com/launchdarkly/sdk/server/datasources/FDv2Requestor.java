package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import okhttp3.Headers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    CompletableFuture<List<FDv2PollingResponse>> Poll(Selector selector);
}
