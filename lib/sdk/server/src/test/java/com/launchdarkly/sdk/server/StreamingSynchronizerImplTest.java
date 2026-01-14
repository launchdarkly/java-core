package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;
import org.junit.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.ComponentsImpl.toHttpProperties;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class StreamingSynchronizerImplTest extends BaseTest {

    private SelectorSource mockSelectorSource() {
        SelectorSource source = mock(SelectorSource.class);
        when(source.getSelector()).thenReturn(Selector.EMPTY);
        return source;
    }

    private static String makeEvent(String type, String data) {
        return "event: " + type + "\ndata: " + data;
    }

    @Test
    public void receivesMultipleChangesets() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred1 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String putObject1 = makeEvent("put-object", "{\"kind\":\"flag\",\"key\":\"flag1\",\"version\":1,\"object\":{}}");
        String payloadTransferred2 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:101)\",\"version\":101}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred1),
                Handlers.SSE.event(putObject1),
                Handlers.SSE.event(payloadTransferred2),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            // First changeset
            CompletableFuture<FDv2SourceResult> result1Future = synchronizer.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result1);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());
            assertNotNull(result1.getChangeSet());

            // Second changeset
            CompletableFuture<FDv2SourceResult> result2Future = synchronizer.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result2.getResultType());
            assertNotNull(result2.getChangeSet());

            synchronizer.shutdown();
        }
    }

    @Test
    public void httpNonRecoverableError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());

            synchronizer.shutdown();
        }
    }

    @Test
    public void httpRecoverableError() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());

            synchronizer.shutdown();
        }
    }

    @Test
    public void networkError() throws Exception {
        // Use an invalid port to simulate network error
        HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
        SelectorSource selectorSource = mockSelectorSource();

        StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                httpProperties,
                URI.create("http://localhost:1"), // invalid port
                "/stream",
                testLogger,
                selectorSource
        );

        CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result.getStatus().getErrorInfo().getKind());

        synchronizer.shutdown();
    }

    @Test
    public void invalidEventData() throws Exception {
        String badEvent = makeEvent("server-intent", "invalid json");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(badEvent),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());

            synchronizer.shutdown();
        }
    }

    @Test
    public void shutdownBeforeEventReceived() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.hang()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> nextFuture = synchronizer.next();

            // Wait a bit then shutdown
            Thread.sleep(100);
            synchronizer.shutdown();

            FDv2SourceResult result = nextFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
            assertNull(result.getStatus().getErrorInfo());
        }
    }

    @Test
    public void shutdownAfterEventReceived() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Shutdown after receiving event should still work
            synchronizer.shutdown();
        }
    }

    @Test
    public void goodbyeEventInResponse() throws Exception {
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(goodbyeEvent),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.GOODBYE, result.getStatus().getState());

            synchronizer.shutdown();
        }
    }

    @Test
    public void heartbeatEvent() throws Exception {
        String heartbeatEvent = makeEvent("heartbeat", "{}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(heartbeatEvent),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Heartbeat should be ignored, and we should get the changeset
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            synchronizer.shutdown();
        }
    }

    @Test
    public void selectorWithVersionAndState() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());

            SelectorSource selectorSource = mock(SelectorSource.class);
            when(selectorSource.getSelector()).thenReturn(Selector.make(50, "(p:old:50)"));

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify selector was fetched when connecting
            verify(selectorSource, atLeastOnce()).getSelector();

            // Verify the request had the correct query parameters
            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("version=50"));
            assertThat(request.getQuery(), containsString("state="));

            synchronizer.shutdown();
        }
    }

    @Test
    public void selectorRefetchedOnReconnection() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // Test reconnection with a 503 error followed by successful connection
        // Add multiple successful handlers in case EventSource reconnects multiple times
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());

            SelectorSource selectorSource = mock(SelectorSource.class);
            when(selectorSource.getSelector())
                    .thenReturn(Selector.make(50, "(p:old:50)"))
                    .thenReturn(Selector.make(100, "(p:new:100)"));

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource
            );

            // First result should be an error from the 503
            CompletableFuture<FDv2SourceResult> result1Future = synchronizer.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result1.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result1.getStatus().getState());

            // Keep getting results until we get a CHANGE_SET (reconnection successful)
            // There may be multiple STATUS results if reconnection takes multiple attempts
            FDv2SourceResult changesetResult = null;
            for (int i = 0; i < 5; i++) {  // Try up to 5 times
                CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
                FDv2SourceResult result = resultFuture.get(15, TimeUnit.SECONDS);
                assertNotNull(result);
                if (result.getResultType() == FDv2SourceResult.ResultType.CHANGE_SET) {
                    changesetResult = result;
                    break;
                }
                // If it's another STATUS, that's fine, just keep waiting for the changeset
                assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            }

            assertNotNull("Should eventually get a CHANGE_SET after reconnection", changesetResult);

            // Verify selector was fetched at least twice (initial failed connect + successful reconnect)
            verify(selectorSource, atLeast(2)).getSelector();

            // Verify we made at least 2 requests
            assertTrue("Should have made at least 2 requests", server.getRecorder().count() >= 2);

            synchronizer.shutdown();
        }
    }
}
