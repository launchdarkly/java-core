package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.SerializationException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of FDv2Requestor for polling feature flag data via FDv2 protocol.
 */
public class DefaultFDv2Requestor implements FDv2Requestor {
    private static final String VERSION_QUERY_PARAM = "version";
    private static final String STATE_QUERY_PARAM = "state";

    private final OkHttpClient httpClient;
    private final URI pollingUri;
    private final Headers headers;
    private final LDLogger logger;
    private final Map<URI, String> etags;

    /**
     * Creates a DefaultFDv2Requestor.
     *
     * @param httpProperties HTTP configuration properties
     * @param baseUri base URI for the FDv2 polling endpoint
     * @param requestPath the request path to append to the base URI (e.g., "/sdk/poll")
     * @param logger logger for diagnostic output
     */
    public DefaultFDv2Requestor(HttpProperties httpProperties, URI baseUri, String requestPath, LDLogger logger) {
        this.logger = logger;
        this.pollingUri = HttpHelpers.concatenateUriPath(baseUri, requestPath);
        this.etags = new HashMap<>();

        OkHttpClient.Builder httpBuilder = httpProperties.toHttpClientBuilder();
        this.headers = httpProperties.toHeadersBuilder().build();
        this.httpClient = httpBuilder.build();
    }

    @Override
    public CompletableFuture<List<FDv2PollingResponse>> Poll(Selector selector) {
        CompletableFuture<List<FDv2PollingResponse>> future = new CompletableFuture<>();

        try {
            // Build the request URI with query parameters
            URI requestUri = pollingUri;

            if (selector.getVersion() > 0) {
                requestUri = HttpHelpers.addQueryParam(requestUri, VERSION_QUERY_PARAM, String.valueOf(selector.getVersion()));
            }

            if (selector.getState() != null && !selector.getState().isEmpty()) {
                requestUri = HttpHelpers.addQueryParam(requestUri, STATE_QUERY_PARAM, selector.getState());
            }

            logger.debug("Making FDv2 polling request to: {}", requestUri);

            // Build the HTTP request
            Request.Builder requestBuilder = new Request.Builder()
                .url(requestUri.toURL())
                .headers(headers)
                .get();

            // Add ETag if we have one cached for this URI
            synchronized (etags) {
                String etag = etags.get(requestUri);
                if (etag != null) {
                    requestBuilder.header("If-None-Match", etag);
                }
            }

            Request request = requestBuilder.build();
            final URI finalRequestUri = requestUri;

            // Make asynchronous HTTP call
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@Nonnull Call call, @Nonnull IOException e) {
                    if (e instanceof SocketTimeoutException) {
                        future.completeExceptionally(
                            new IOException("FDv2 polling request timed out: " + finalRequestUri, e)
                        );
                    } else {
                        future.completeExceptionally(e);
                    }
                }

                @Override
                public void onResponse(@Nonnull Call call, @Nonnull Response response) {
                    try {
                        // Handle 304 Not Modified - no new data
                        if (response.code() == 304) {
                            logger.debug("FDv2 polling request returned 304: not modified");
                            future.complete(Collections.emptyList());
                            return;
                        }

                        if (!response.isSuccessful()) {
                            future.completeExceptionally(
                                new IOException("FDv2 polling request failed with status code: " + response.code())
                            );
                            return;
                        }

                        // Update ETag cache
                        String newEtag = response.header("ETag");
                        synchronized (etags) {
                            if (newEtag != null) {
                                etags.put(finalRequestUri, newEtag);
                            } else {
                                etags.remove(finalRequestUri);
                            }
                        }

                        // Parse the response body
                        if (response.body() == null) {
                            future.completeExceptionally(new IOException("Response body is null"));
                            return;
                        }

                        String responseBody = response.body().string();
                        logger.debug("Received FDv2 polling response");

                        List<FDv2Event> events = FDv2Event.parseEventsArray(responseBody);

                        // Create and return the response
                        FDv2PollingResponse pollingResponse = new FDv2PollingResponse(events, response.headers());
                        future.complete(Collections.singletonList(pollingResponse));

                    } catch (IOException | SerializationException e) {
                        future.completeExceptionally(e);
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Closes the HTTP client and releases resources.
     */
    public void close() {
        HttpProperties.shutdownHttpClient(httpClient);
    }
}
