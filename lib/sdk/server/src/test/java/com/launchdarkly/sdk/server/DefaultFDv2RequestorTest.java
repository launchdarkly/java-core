package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class DefaultFDv2RequestorTest extends BaseTest {
    private static final String SDK_KEY = "sdk-key";
    private static final String REQUEST_PATH = "/sdk/poll";

    // Valid FDv2 polling response with multiple events
    private static final String VALID_EVENTS_JSON = "{\n" +
        "  \"events\": [\n" +
        "    {\n" +
        "      \"event\": \"server-intent\",\n" +
        "      \"data\": {\n" +
        "        \"payloads\": [{\n" +
        "          \"id\": \"payload-1\",\n" +
        "          \"target\": 100,\n" +
        "          \"intentCode\": \"xfer-full\",\n" +
        "          \"reason\": \"payload-missing\"\n" +
        "        }]\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"put-object\",\n" +
        "      \"data\": {\n" +
        "        \"version\": 150,\n" +
        "        \"kind\": \"flag\",\n" +
        "        \"key\": \"test-flag\",\n" +
        "        \"object\": {\n" +
        "          \"key\": \"test-flag\",\n" +
        "          \"version\": 1,\n" +
        "          \"on\": true,\n" +
        "          \"fallthrough\": { \"variation\": 0 },\n" +
        "          \"offVariation\": 1,\n" +
        "          \"variations\": [true, false],\n" +
        "          \"salt\": \"test-salt\",\n" +
        "          \"trackEvents\": false,\n" +
        "          \"trackEventsFallthrough\": false,\n" +
        "          \"debugEventsUntilDate\": null,\n" +
        "          \"clientSide\": false,\n" +
        "          \"deleted\": false\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"payload-transferred\",\n" +
        "      \"data\": {\n" +
        "        \"state\": \"(p:payload-1:100)\",\n" +
        "        \"version\": 100\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    // Empty events array
    private static final String EMPTY_EVENTS_JSON = "{\"events\": []}";

    private DefaultFDv2Requestor makeRequestor(HttpServer server) {
        return makeRequestor(server, LDConfig.DEFAULT);
    }

    private DefaultFDv2Requestor makeRequestor(HttpServer server, LDConfig config) {
        return new DefaultFDv2Requestor(makeHttpConfig(config), server.getUri(), REQUEST_PATH, testLogger);
    }

    private HttpProperties makeHttpConfig(LDConfig config) {
        return ComponentsImpl.toHttpProperties(config.http.build(new ClientContext(SDK_KEY)));
    }

    @Test
    public void successfulRequestWithEvents() throws Exception {
        Handler resp = Handlers.bodyJson(VALID_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertNotNull(response.getEvents());
                assertEquals(3, response.getEvents().size());

                List<FDv2Event> events = response.getEvents();
                assertEquals("server-intent", events.get(0).getEventType());
                assertEquals("put-object", events.get(1).getEventType());
                assertEquals("payload-transferred", events.get(2).getEventType());

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req.getPath());
            }
        }
    }

    @Test
    public void emptyEventsArray() throws Exception {
        Handler resp = Handlers.bodyJson(EMPTY_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertNotNull(response.getEvents());
                assertTrue(response.getEvents().isEmpty());
            }
        }
    }

    @Test
    public void requestWithBasisQueryParameter() throws Exception {
        Handler resp = Handlers.bodyJson(EMPTY_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Selector selector = Selector.make(42, "test-state");

                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(selector);

                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req.getPath());
                assertThat(req.getQuery(), containsString("basis=test-state"));
            }
        }
    }

    @Test
    public void requestWithBasisContainingState() throws Exception {
        Handler resp = Handlers.bodyJson(EMPTY_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Selector selector = Selector.make(0, "(p:payload-1:100)");

                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(selector);

                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req.getPath());
                assertThat(req.getQuery(), containsString("basis=%28p%3Apayload-1%3A100%29"));
            }
        }
    }

    @Test
    public void requestWithComplexBasisState() throws Exception {
        Handler resp = Handlers.bodyJson(EMPTY_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Selector selector = Selector.make(100, "(p:my-payload:200)");

                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(selector);

                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req.getPath());
                assertThat(req.getQuery(), containsString("basis=%28p%3Amy-payload%3A200%29"));
            }
        }
    }

    @Test
    public void etagCachingWith304NotModified() throws Exception {
        Handler cacheableResp = Handlers.all(
            Handlers.header("ETag", "my-etag-value"),
            Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler cachedResp = Handlers.status(304);
        Handler sequence = Handlers.sequential(cacheableResp, cachedResp);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                // First request should succeed and cache the ETag
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future1 =
                    requestor.Poll(Selector.EMPTY);

                FDv2Requestor.FDv2PayloadResponse response1 = future1.get(5, TimeUnit.SECONDS);
                assertNotNull(response1);
                assertEquals(3, response1.getEvents().size());

                RequestInfo req1 = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req1.getPath());
                assertEquals(null, req1.getHeader("If-None-Match"));

                // Second request should send If-None-Match and receive 304
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future2 =
                    requestor.Poll(Selector.EMPTY);

                FDv2Requestor.FDv2PayloadResponse response2 = future2.get(5, TimeUnit.SECONDS);
                assertEquals(null, response2);

                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals(REQUEST_PATH, req2.getPath());
                assertEquals("my-etag-value", req2.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void etagUpdatedOnNewResponse() throws Exception {
        Handler resp1 = Handlers.all(
            Handlers.header("ETag", "etag-1"),
            Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler resp2 = Handlers.all(
            Handlers.header("ETag", "etag-2"),
            Handlers.bodyJson(EMPTY_EVENTS_JSON)
        );
        Handler resp3 = Handlers.status(304);
        Handler sequence = Handlers.sequential(resp1, resp2, resp3);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                // First request
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req1 = server.getRecorder().requireRequest();
                assertEquals(null, req1.getHeader("If-None-Match"));

                // Second request should use etag-1
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-1", req2.getHeader("If-None-Match"));

                // Third request should use etag-2
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertEquals("etag-2", req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void etagRemovedWhenNotInResponse() throws Exception {
        Handler resp1 = Handlers.all(
            Handlers.header("ETag", "etag-1"),
            Handlers.bodyJson(VALID_EVENTS_JSON)
        );
        Handler resp2 = Handlers.bodyJson(EMPTY_EVENTS_JSON);  // No ETag
        Handler resp3 = Handlers.bodyJson(EMPTY_EVENTS_JSON);  // Third request
        Handler sequence = Handlers.sequential(resp1, resp2, resp3);

        try (HttpServer server = HttpServer.start(sequence)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                // First request with ETag
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                server.getRecorder().requireRequest();

                // Second request should use etag-1
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-1", req2.getHeader("If-None-Match"));

                // Third request should not send ETag (was removed)
                requestor.Poll(Selector.EMPTY).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertEquals(null, req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void httpErrorCodeThrowsException() throws Exception {
        Handler resp = Handlers.status(500);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                try {
                    future.get(5, TimeUnit.SECONDS);
                    fail("Expected ExecutionException");
                } catch (ExecutionException e) {
                    assertThat(e.getCause(), notNullValue());
                    assertThat(e.getCause().getMessage(), containsString("500"));
                }
            }
        }
    }

    @Test
    public void http404ThrowsException() throws Exception {
        Handler resp = Handlers.status(404);

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                try {
                    future.get(5, TimeUnit.SECONDS);
                    fail("Expected ExecutionException");
                } catch (ExecutionException e) {
                    assertThat(e.getCause(), notNullValue());
                    assertThat(e.getCause().getMessage(), containsString("404"));
                }
            }
        }
    }

    @Test
    public void invalidJsonThrowsException() throws Exception {
        Handler resp = Handlers.bodyJson("{ invalid json }");

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                try {
                    future.get(5, TimeUnit.SECONDS);
                    fail("Expected ExecutionException");
                } catch (ExecutionException e) {
                    assertThat(e.getCause(), notNullValue());
                }
            }
        }
    }

    @Test
    public void missingEventsPropertyThrowsException() throws Exception {
        Handler resp = Handlers.bodyJson("{}");

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                try {
                    future.get(5, TimeUnit.SECONDS);
                    fail("Expected ExecutionException");
                } catch (ExecutionException e) {
                    assertThat(e.getCause(), notNullValue());
                }
            }
        }
    }

    @Test
    public void baseUriCanHaveContextPath() throws Exception {
        Handler resp = Handlers.bodyJson(EMPTY_EVENTS_JSON);

        try (HttpServer server = HttpServer.start(resp)) {
            URI uri = server.getUri().resolve("/context/path");

            try (DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
                    makeHttpConfig(LDConfig.DEFAULT), uri, REQUEST_PATH, testLogger)) {

                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                future.get(5, TimeUnit.SECONDS);

                RequestInfo req = server.getRecorder().requireRequest();
                assertEquals("/context/path" + REQUEST_PATH, req.getPath());
            }
        }
    }

    @Test
    public void differentSelectorsUseDifferentEtags() throws Exception {
        Handler resp = Handlers.all(
            Handlers.header("ETag", "etag-for-request"),
            Handlers.bodyJson(EMPTY_EVENTS_JSON)
        );

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                Selector selector1 = Selector.make(100, "(p:payload-1:100)");
                Selector selector2 = Selector.make(200, "(p:payload-2:200)");

                // First request with selector1
                requestor.Poll(selector1).get(5, TimeUnit.SECONDS);
                RequestInfo req1 = server.getRecorder().requireRequest();
                assertEquals(null, req1.getHeader("If-None-Match"));

                // Second request with selector1 should use cached ETag
                requestor.Poll(selector1).get(5, TimeUnit.SECONDS);
                RequestInfo req2 = server.getRecorder().requireRequest();
                assertEquals("etag-for-request", req2.getHeader("If-None-Match"));

                // Request with selector2 should not have ETag (different URI)
                requestor.Poll(selector2).get(5, TimeUnit.SECONDS);
                RequestInfo req3 = server.getRecorder().requireRequest();
                assertEquals(null, req3.getHeader("If-None-Match"));
            }
        }
    }

    @Test
    public void responseHeadersAreIncluded() throws Exception {
        Handler resp = Handlers.all(
            Handlers.header("X-Custom-Header", "custom-value"),
            Handlers.bodyJson(EMPTY_EVENTS_JSON)
        );

        try (HttpServer server = HttpServer.start(resp)) {
            try (DefaultFDv2Requestor requestor = makeRequestor(server)) {
                CompletableFuture<FDv2Requestor.FDv2PayloadResponse> future =
                    requestor.Poll(Selector.EMPTY);

                FDv2Requestor.FDv2PayloadResponse response = future.get(5, TimeUnit.SECONDS);

                assertNotNull(response);
                assertNotNull(response.getHeaders());
                assertEquals("custom-value", response.getHeaders().get("X-Custom-Header"));
            }
        }
    }
}