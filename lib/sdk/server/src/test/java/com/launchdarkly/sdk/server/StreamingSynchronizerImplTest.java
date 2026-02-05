package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.ComponentsImpl.toHttpProperties;
import static com.launchdarkly.sdk.server.TestComponents.basicDiagnosticStore;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
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

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());
            assertNull(result.getStatus().getReason());

            synchronizer.close();
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
                selectorSource,
                    null,
                Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
        );

        CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result.getStatus().getErrorInfo().getKind());

        synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> nextFuture = synchronizer.next();

            // Wait a bit then shutdown
            Thread.sleep(100);
            synchronizer.close();

            FDv2SourceResult result = nextFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
            assertNull(result.getStatus().getErrorInfo());
            assertNull(result.getStatus().getReason());
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Shutdown after receiving event should still work
            synchronizer.close();
        }
    }

    @Test
    public void goodbyeEventInResponse() throws Exception {
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // First connection: send goodbye, then second connection: send changeset to verify restart
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(goodbyeEvent),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            // First result should be goodbye
            CompletableFuture<FDv2SourceResult> result1Future = synchronizer.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result1);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result1.getResultType());
            assertEquals(FDv2SourceResult.State.GOODBYE, result1.getStatus().getState());
            assertEquals("service-unavailable", result1.getStatus().getReason());

            // Second result should be a changeset from the restarted stream
            CompletableFuture<FDv2SourceResult> result2Future = synchronizer.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);

            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result2.getResultType());
            assertNotNull(result2.getChangeSet());

            // Verify we made 2 requests (initial connection + reconnection after goodbye)
            assertTrue("Should have made at least 2 requests", server.getRecorder().count() >= 2);

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            // Heartbeat should be ignored, and we should get the changeset
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify selector was fetched when connecting
            verify(selectorSource, atLeastOnce()).getSelector();

            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("basis=%28p%3Aold%3A50%29"));

            synchronizer.close();
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
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

            synchronizer.close();
        }
    }

    @Test
    public void errorEventFromServer() throws Exception {
        String errorEvent = makeEvent("error", "{\"id\":\"error-123\",\"reason\":\"some server error\"}");
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(errorEvent),
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            // Error event should be logged but not queued, so we should get the changeset
            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            synchronizer.close();
        }
    }

    @Test
    public void selectorWithVersionOnly() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());

            SelectorSource selectorSource = mock(SelectorSource.class);
            when(selectorSource.getSelector()).thenReturn(Selector.make(75, "(p:test:75)"));

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("basis=%28p%3Atest%3A75%29"));

            synchronizer.close();
        }
    }

    @Test
    public void selectorWithEmptyState() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());

            SelectorSource selectorSource = mock(SelectorSource.class);
            when(selectorSource.getSelector()).thenReturn(Selector.make(80, "(p:empty-test:80)"));

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("basis=%28p%3Aempty-test%3A80%29"));

            synchronizer.close();
        }
    }

    @Test
    public void closeCalledMultipleTimes() throws Exception {
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            // Call close multiple times - should not throw exceptions
            synchronizer.close();
            synchronizer.close();
            synchronizer.close();

            // next() should still return shutdown
            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
        }
    }

    @Test
    public void invalidEventStructureCausesInterrupt() throws Exception {
        // Event with missing required fields - should cause protocol handler to fail
        String badEventStructure = makeEvent("put-object", "{}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(badEventStructure),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());

            synchronizer.close();
        }
    }

    @Test
    public void payloadFilterIsAddedToRequest() throws Exception {
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
                    selectorSource,
                    "myFilter",
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify the request had the filter parameter
            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("filter=myFilter"));

            synchronizer.close();
        }
    }

    @Test
    public void payloadFilterWithSelectorBothAddedToRequest() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.SSE.start(),
                Handlers.SSE.event(serverIntent),
                Handlers.SSE.event(payloadTransferred),
                Handlers.SSE.leaveOpen()))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());

            SelectorSource selectorSource = mock(SelectorSource.class);
            when(selectorSource.getSelector()).thenReturn(Selector.make(42, "(p:test:42)"));

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    "testFilter",
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), containsString("filter=testFilter"));
            assertThat(request.getQuery(), containsString("basis=%28p%3Atest%3A42%29"));

            synchronizer.close();
        }
    }

    @Test
    public void emptyPayloadFilterNotAddedToRequest() throws Exception {
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
                    selectorSource,
                    "",
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify the request did not have the filter parameter
            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), not(containsString("filter")));

            synchronizer.close();
        }
    }

    @Test
    public void nullPayloadFilterNotAddedToRequest() throws Exception {
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify the request did not have the filter parameter
            assertEquals(1, server.getRecorder().count());
            RequestInfo request = server.getRecorder().requireRequest();
            assertThat(request.getQuery(), not(containsString("filter")));

            synchronizer.close();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToTrueInSuccessResponse() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "true"),
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertEquals(true, result.isFdv1Fallback());

            synchronizer.close();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToFalseWhenHeaderNotPresent() throws Exception {
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertEquals(false, result.isFdv1Fallback());

            synchronizer.close();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToTrueInErrorResponse() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.status(503),
                Handlers.header("x-ld-fd-fallback", "true")))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(true, result.isFdv1Fallback());

            synchronizer.close();
        }
    }

    @Test
    public void environmentIdExtractedFromHeaders() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-envid", "test-env-streaming"),
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());
            assertEquals("test-env-streaming", result.getChangeSet().getEnvironmentId());

            synchronizer.close();
        }
    }

    @Test
    public void bothFdv1FallbackAndEnvironmentIdExtractedFromHeaders() throws Exception {
        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "true"),
                Handlers.header("x-ld-envid", "test-env-combined-streaming"),
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertEquals(true, result.isFdv1Fallback());
            assertNotNull(result.getChangeSet());
            assertEquals("test-env-combined-streaming", result.getChangeSet().getEnvironmentId());

            synchronizer.close();
        }
    }

    @Test
    public void serializationExceptionWithoutFallbackHeader() throws Exception {
        // Verify that fallback is false when header is not present
        // Use malformed JSON that will cause parsing to fail
        String badEvent = makeEvent("server-intent", "{");

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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());
            assertEquals(false, result.isFdv1Fallback());

            synchronizer.close();
        }
    }

    @Test
    public void serializationExceptionPreservesFallbackHeader() throws Exception {
        // Test that when SerializationException occurs, the fallback header from the event is preserved
        // Use definitely malformed JSON that will cause parsing to fail
        String badEvent = makeEvent("server-intent", "{");

        try (HttpServer server = HttpServer.start(Handlers.all(
                Handlers.header("x-ld-fd-fallback", "true"),
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());
            assertEquals(true, result.isFdv1Fallback());

            synchronizer.close();
        }
    }

    @Test
    public void streamInitDiagnosticRecordedOnSuccessfulChangeset() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();
        long startTime = System.currentTimeMillis();

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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    diagnosticStore
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            long timeAfterOpen = System.currentTimeMillis();
            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(1, streamInits.size());
            LDValue init = streamInits.get(0);
            assertFalse(init.get("failed").booleanValue());
            assertThat(init.get("timestamp").longValue(),
                allOf(greaterThanOrEqualTo(startTime), lessThanOrEqualTo(timeAfterOpen)));
            assertThat(init.get("durationMillis").longValue(), lessThanOrEqualTo(timeAfterOpen - startTime));

            synchronizer.close();
        }
    }

    @Test
    public void streamInitDiagnosticRecordedOnErrorDuringInit() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();
        long startTime = System.currentTimeMillis();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");

        // First connection: 503 error, second connection: successful changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.status(503),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred),
                        Handlers.SSE.leaveOpen())))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    diagnosticStore
            );

            // First result should be the error
            CompletableFuture<FDv2SourceResult> result1Future = synchronizer.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result1.getResultType());

            // Second result should be the successful changeset
            CompletableFuture<FDv2SourceResult> result2Future = synchronizer.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result2.getResultType());

            long timeAfterOpen = System.currentTimeMillis();
            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();

            LDValue streamInits = event.get("streamInits");
            assertEquals(2, streamInits.size());
            LDValue init0 = streamInits.get(0);
            assertTrue(init0.get("failed").booleanValue());
            assertThat(init0.get("timestamp").longValue(),
                allOf(greaterThanOrEqualTo(startTime), lessThanOrEqualTo(timeAfterOpen)));
            assertThat(init0.get("durationMillis").longValue(), lessThanOrEqualTo(timeAfterOpen - startTime));

            LDValue init1 = streamInits.get(1);
            assertFalse(init1.get("failed").booleanValue());
            assertThat(init1.get("timestamp").longValue(),
                allOf(greaterThanOrEqualTo(init0.get("timestamp").longValue()), lessThanOrEqualTo(timeAfterOpen)));

            synchronizer.close();
        }
    }

    @Test
    public void streamRestartNotRecordedAsFailed() throws Exception {
        DiagnosticStore diagnosticStore = basicDiagnosticStore();

        String serverIntent = makeEvent("server-intent", "{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}");
        String payloadTransferred1 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:100)\",\"version\":100}");
        String goodbyeEvent = makeEvent("goodbye", "{\"reason\":\"service-unavailable\"}");
        String payloadTransferred2 = makeEvent("payload-transferred", "{\"state\":\"(p:payload-1:101)\",\"version\":101}");

        // First connection: changeset + goodbye, second connection: changeset
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred1),
                        Handlers.SSE.event(goodbyeEvent),
                        Handlers.SSE.leaveOpen()),
                Handlers.all(
                        Handlers.SSE.start(),
                        Handlers.SSE.event(serverIntent),
                        Handlers.SSE.event(payloadTransferred2),
                        Handlers.SSE.leaveOpen())))) {

            HttpProperties httpProperties = toHttpProperties(clientContext("sdk-key", baseConfig().build()).getHttp());
            SelectorSource selectorSource = mockSelectorSource();

            StreamingSynchronizerImpl synchronizer = new StreamingSynchronizerImpl(
                    httpProperties,
                    server.getUri(),
                    "/stream",
                    testLogger,
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    diagnosticStore
            );

            // First changeset
            CompletableFuture<FDv2SourceResult> result1Future = synchronizer.next();
            FDv2SourceResult result1 = result1Future.get(5, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());

            // Goodbye
            CompletableFuture<FDv2SourceResult> result2Future = synchronizer.next();
            FDv2SourceResult result2 = result2Future.get(5, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result2.getResultType());
            assertEquals(FDv2SourceResult.State.GOODBYE, result2.getStatus().getState());

            // Second changeset after reconnect
            CompletableFuture<FDv2SourceResult> result3Future = synchronizer.next();
            FDv2SourceResult result3 = result3Future.get(5, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result3.getResultType());

            LDValue event = diagnosticStore.createEventAndReset(0, 0).getJsonValue();
            LDValue streamInits = event.get("streamInits");
            assertEquals(2, streamInits.size());
            // Both inits should be successful (goodbye is a deliberate restart, not a failure)
            assertFalse(streamInits.get(0).get("failed").booleanValue());
            assertFalse(streamInits.get(1).get("failed").booleanValue());

            synchronizer.close();
        }
    }

    @Test
    public void nullDiagnosticStoreDoesNotCauseError() throws Exception {
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
                    selectorSource,
                    null,
                    Duration.ofMillis(100),
                    Thread.NORM_PRIORITY,
                    null  // null diagnosticStore
            );

            CompletableFuture<FDv2SourceResult> resultFuture = synchronizer.next();
            FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());

            synchronizer.close();
        }
    }

}
