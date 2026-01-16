package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpErrors;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class PollingInitializerImplTest extends BaseTest {

    private FDv2Requestor mockRequestor() {
        return mock(FDv2Requestor.class);
    }

    private SelectorSource mockSelectorSource() {
        SelectorSource source = mock(SelectorSource.class);
        when(source.getSelector()).thenReturn(Selector.EMPTY);
        return source;
    }

    // Helper for Java 8 compatibility - failedFuture() is Java 9+
    private <T> CompletableFuture<T> failedFuture(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    private FDv2Requestor.FDv2PayloadResponse makeSuccessResponse() {
        String json = "{\n" +
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
            "      \"event\": \"payload-transferred\",\n" +
            "      \"data\": {\n" +
            "        \"state\": \"(p:payload-1:100)\",\n" +
            "        \"version\": 100\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        try {
            return new FDv2Requestor.FDv2PayloadResponse(
                com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(json),
                okhttp3.Headers.of()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void successfulInitialization() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());
        assertNotNull(result.getChangeSet());

        verify(requestor, times(1)).Poll(any(Selector.class));
    }

    @Test
    public void httpRecoverableError() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(failedFuture(new HttpErrors.HttpErrorException(503)));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertNotNull(result.getStatus().getErrorInfo());
        assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());

        
    }

    @Test
    public void httpNonRecoverableError() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(failedFuture(new HttpErrors.HttpErrorException(401)));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result.getStatus().getErrorInfo().getKind());

        
    }

    @Test
    public void networkError() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(failedFuture(new IOException("Connection refused")));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result.getStatus().getErrorInfo().getKind());

        
    }

    @Test
    public void serializationError() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(failedFuture(new SerializationException(new Exception("Invalid JSON"))));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());

        
    }

    @Test
    public void shutdownBeforePollCompletes() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        CompletableFuture<FDv2Requestor.FDv2PayloadResponse> delayedResponse = new CompletableFuture<>();
        when(requestor.Poll(any(Selector.class))).thenReturn(delayedResponse);

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();

        // Shutdown before poll completes
        Thread.sleep(100);
        initializer.close();

        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
        assertNull(result.getStatus().getErrorInfo());

        
    }

    @Test
    public void shutdownAfterPollCompletes() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

        // Shutdown after completion should still work
        initializer.close();

        
    }

    @Test
    public void errorEventInResponse() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        String errorJson = "{\n" +
            "  \"events\": [\n" +
            "    {\n" +
            "      \"event\": \"error\",\n" +
            "      \"data\": {\n" +
            "        \"error\": \"invalid-request\",\n" +
            "        \"reason\": \"bad request\"\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        FDv2Requestor.FDv2PayloadResponse response = new FDv2Requestor.FDv2PayloadResponse(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(errorJson),
            okhttp3.Headers.of()
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());

        
    }

    @Test
    public void goodbyeEventInResponse() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        String goodbyeJson = "{\n" +
            "  \"events\": [\n" +
            "    {\n" +
            "      \"event\": \"goodbye\",\n" +
            "      \"data\": {\n" +
            "        \"reason\": \"service-unavailable\"\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        FDv2Requestor.FDv2PayloadResponse response = new FDv2Requestor.FDv2PayloadResponse(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(goodbyeJson),
            okhttp3.Headers.of()
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.GOODBYE, result.getStatus().getState());

        
    }

    @Test
    public void emptyEventsArray() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        String emptyJson = "{\"events\": []}";

        FDv2Requestor.FDv2PayloadResponse response = new FDv2Requestor.FDv2PayloadResponse(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(emptyJson),
            okhttp3.Headers.of()
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        // Empty events array should result in terminal error
        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());


    }

    @Test
    public void internalErrorWithInvalidDataKind() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        // Create a response with malformed payload-transferred event. `state->states`.
        // This will trigger JSON_ERROR internal error which maps to INVALID_DATA
        String malformedPayloadTransferred = "{\n" +
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
            "      \"event\": \"payload-transferred\",\n" +
            "      \"data\": {\n" +
            "        \"states\": \"(p:payload-1:100)\",\n" +
            "        \"version\": 100\n" +
            "      }\n" +
            "    },\n" +
            "    {\n" +
            "      \"event\": \"put-object\",\n" +
            "      \"data\": {}\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        FDv2Requestor.FDv2PayloadResponse response = new FDv2Requestor.FDv2PayloadResponse(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(malformedPayloadTransferred),
            okhttp3.Headers.of()
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result.getStatus().getErrorInfo().getKind());


    }

    @Test
    public void internalErrorWithUnknownKind() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();

        // Create a response with an unrecognized event type
        // This will trigger UNKNOWN_EVENT internal error which maps to UNKNOWN error kind
        String unknownEventJson = "{\n" +
            "  \"events\": [\n" +
            "    {\n" +
            "      \"event\": \"unrecognized-event-type\",\n" +
            "      \"data\": {}\n" +
            "    }\n" +
            "  ]\n" +
            "}";

        FDv2Requestor.FDv2PayloadResponse response = new FDv2Requestor.FDv2PayloadResponse(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(unknownEventJson),
            okhttp3.Headers.of()
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        PollingInitializerImpl initializer = new PollingInitializerImpl(requestor, testLogger, selectorSource);

        CompletableFuture<FDv2SourceResult> resultFuture = initializer.run();
        FDv2SourceResult result = resultFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());
        assertEquals(DataSourceStatusProvider.ErrorKind.UNKNOWN, result.getStatus().getErrorInfo().getKind());


    }
}
