package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("javadoc")
public class PollingSynchronizerImplTest extends BaseTest {

    private FDv2Requestor mockRequestor() {
        return mock(FDv2Requestor.class);
    }

    private SelectorSource mockSelectorSource() {
        SelectorSource source = mock(SelectorSource.class);
        when(source.getSelector()).thenReturn(Selector.EMPTY);
        return source;
    }

    // Helper for Java 8 compatibility - CompletableFuture.failedFuture() is Java 9+
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
            return FDv2Requestor.FDv2PayloadResponse.success(
                    com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(json),
                    okhttp3.Headers.of(),
                    200
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void nextWaitsWhenQueueEmpty() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        // Delay the response so queue is initially empty
        CompletableFuture<FDv2Requestor.FDv2PayloadResponse> delayedResponse = new CompletableFuture<>();
        when(requestor.Poll(any(Selector.class))).thenReturn(delayedResponse);

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            CompletableFuture<FDv2SourceResult> nextFuture = synchronizer.next();

            // Verify future is not complete yet
            Thread.sleep(50);
            assertEquals(false, nextFuture.isDone());

            // Complete the delayed response
            delayedResponse.complete(makeSuccessResponse());

            // Now the future should complete
            FDv2SourceResult result = nextFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void nextReturnsImmediatelyWhenResultQueued() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for first poll to complete and queue result
            Thread.sleep(150);

            // Now next() should return immediately
            CompletableFuture<FDv2SourceResult> nextFuture = synchronizer.next();
            assertTrue(nextFuture.isDone());

            FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void multipleItemsQueuedReturnedInOrder() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls to complete and queue results
            Thread.sleep(250);

            // Should have at least 3-4 results queued
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());

            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result3);
            assertEquals(SourceResultType.CHANGE_SET, result3.getResultType());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void shutdownBeforeNextCalled() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            // Shutdown immediately
            synchronizer.close();

            // next() should return shutdown result
            FDv2SourceResult result = synchronizer.next().get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void shutdownWhileNextWaiting() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        // Delay the response so next() will be waiting
        CompletableFuture<FDv2Requestor.FDv2PayloadResponse> delayedResponse = new CompletableFuture<>();
        when(requestor.Poll(any(Selector.class))).thenReturn(delayedResponse);

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            CompletableFuture<FDv2SourceResult> nextFuture = synchronizer.next();

            // Verify next() is waiting
            Thread.sleep(50);
            assertEquals(false, nextFuture.isDone());

            // Shutdown while waiting
            synchronizer.close();

            // next() should complete with shutdown result
            FDv2SourceResult result = nextFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.SHUTDOWN, result.getStatus().getState());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void shutdownAfterMultipleItemsQueued() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls to complete
            Thread.sleep(250);

            // Consume one result
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);

            // Shutdown with items still in queue
            synchronizer.close();

            // next() can return either queued items or shutdown
            // Just verify we get valid results and eventually shutdown
            boolean gotShutdown = false;
            for (int i = 0; i < 10; i++) {
                FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);
                assertNotNull(result);
                if (result.getResultType() == SourceResultType.STATUS &&
                        result.getStatus().getState() == SourceSignal.SHUTDOWN) {
                    gotShutdown = true;
                    break;
                }
            }
            assertTrue("Should eventually receive shutdown result", gotShutdown);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void pollingContinuesInBackground() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger pollCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            pollCount.incrementAndGet();
            return CompletableFuture.completedFuture(makeSuccessResponse());
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for several poll intervals
            Thread.sleep(250);

            // Should have polled multiple times
            int count = pollCount.get();
            assertTrue("Expected multiple polls, got " + count, count >= 3);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void errorsInPollingAreSwallowed() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // First and third calls succeed, second fails
            if (count == 2) {
                return failedFuture(new IOException("Network error"));
            } else {
                successCount.incrementAndGet();
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls including the failed one
            Thread.sleep(250);

            // First result should be success
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.CHANGE_SET, result1.getResultType());

            // Second result should be the error (INTERRUPTED status)
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(SourceResultType.STATUS, result2.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result2.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result2.getStatus().getErrorInfo().getKind());

            // Third result should be success again
            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result3);
            assertEquals(SourceResultType.CHANGE_SET, result3.getResultType());

            // Verify polling continued after error
            assertTrue("Should have at least 2 successful polls", successCount.get() >= 2);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void taskCancelledOnShutdown() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger pollCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            pollCount.incrementAndGet();
            return CompletableFuture.completedFuture(makeSuccessResponse());
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            Thread.sleep(100);
            int countBeforeShutdown = pollCount.get();

            synchronizer.close();

            // Wait and verify no more polls occur
            Thread.sleep(200);
            int countAfterShutdown = pollCount.get();

            // Count should not increase significantly after shutdown
            assertTrue("Polling should stop after shutdown",
                    countAfterShutdown <= countBeforeShutdown + 1); // Allow for 1 in-flight poll
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void nullResponseSwallowedInPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // First call returns null (304 Not Modified), subsequent return success
            if (count == 1) {
                return CompletableFuture.completedFuture(null);
            } else {
                successCount.incrementAndGet();
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls
            Thread.sleep(250);

            // Should get success results - null responses cause exceptions that are swallowed
            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());

            // Verify polling continued after null response
            assertTrue("Should have successful polls after null", successCount.get() >= 1);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void multipleConsumersCanCallNext() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PayloadResponse response = makeSuccessResponse();
        when(requestor.Poll(any(Selector.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for some results to queue
            Thread.sleep(200);

            // Multiple consumers get results
            CompletableFuture<FDv2SourceResult> future1 = synchronizer.next();
            CompletableFuture<FDv2SourceResult> future2 = synchronizer.next();
            CompletableFuture<FDv2SourceResult> future3 = synchronizer.next();

            FDv2SourceResult result1 = future1.get(5, TimeUnit.SECONDS);
            FDv2SourceResult result2 = future2.get(5, TimeUnit.SECONDS);
            FDv2SourceResult result3 = future3.get(5, TimeUnit.SECONDS);

            assertNotNull(result1);
            assertNotNull(result2);
            assertNotNull(result3);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void nonRecoverableHttpErrorStopsPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // First call returns 401 (non-recoverable)
            if (count == 1) {
                return CompletableFuture.completedFuture(
                    FDv2Requestor.FDv2PayloadResponse.failure(401, okhttp3.Headers.of()));
            } else {
                // Subsequent calls should not happen, but return success if they do
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // First result should be terminal error
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.TERMINAL_ERROR, result1.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result1.getStatus().getErrorInfo().getKind());

            // Wait to see if polling continues
            Thread.sleep(200);

            // Should have only called requestor once - polling stopped after terminal error
            assertEquals("Polling should have stopped after terminal error", 1, callCount.get());

            // Don't call next() again after terminal error - that's incorrect usage
            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void recoverableHttpErrorContinuesPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // First call returns 429 (recoverable - too many requests)
            if (count == 1) {
                return CompletableFuture.completedFuture(
                    FDv2Requestor.FDv2PayloadResponse.failure(429, okhttp3.Headers.of()));
            } else {
                // Subsequent calls succeed
                successCount.incrementAndGet();
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls
            Thread.sleep(250);

            // First result should be interrupted error
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE, result1.getStatus().getErrorInfo().getKind());

            // Second result should be success (polling continued)
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            // Verify polling continued after recoverable error
            assertTrue("Should have at least 2 successful polls after recoverable error", successCount.get() >= 2);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void multipleRecoverableErrorsContinuePolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // Multiple recoverable errors: 408, 429, network error, success pattern
            if (count == 1) {
                return CompletableFuture.completedFuture(
                    FDv2Requestor.FDv2PayloadResponse.failure(408, okhttp3.Headers.of()));
            } else if (count == 2) {
                return CompletableFuture.completedFuture(
                    FDv2Requestor.FDv2PayloadResponse.failure(429, okhttp3.Headers.of()));
            } else if (count == 3) {
                return failedFuture(new IOException("Connection timeout"));
            } else {
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls
            Thread.sleep(300);

            // Get first three interrupted results
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());

            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result2.getStatus().getState());

            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.INTERRUPTED, result3.getStatus().getState());

            // Fourth result should be success
            FDv2SourceResult result4 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceResultType.CHANGE_SET, result4.getResultType());

            // Verify polling continued through multiple errors
            assertTrue("Should have made at least 4 calls", callCount.get() >= 4);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void nonRecoverableThenRecoverableErrorStopsPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            // First call returns 403 (non-recoverable)
            if (count == 1) {
                return CompletableFuture.completedFuture(
                    FDv2Requestor.FDv2PayloadResponse.failure(403, okhttp3.Headers.of()));
            } else {
                // Any subsequent calls should not happen
                return failedFuture(new IOException("Network error"));
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // First result should be terminal error
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertEquals(SourceSignal.TERMINAL_ERROR, result1.getStatus().getState());

            // Wait to ensure no more polling
            Thread.sleep(200);

            // Should have only called requestor once
            assertEquals("Polling should have stopped after terminal error", 1, callCount.get());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void internalErrorWithInvalidDataKindContinuesPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // First call returns response with malformed payload transfer. state->states
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

                return CompletableFuture.completedFuture(FDv2Requestor.FDv2PayloadResponse.success(
                    com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(malformedPayloadTransferred),
                    okhttp3.Headers.of(),
                    200
                ));
            } else {
                // Subsequent calls succeed
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls
            Thread.sleep(250);

            // First result should be interrupted with INVALID_DATA error kind
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.INVALID_DATA, result1.getStatus().getErrorInfo().getKind());

            // Second result should be success (polling continued)
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            // Verify polling continued after internal error
            assertTrue("Should have made at least 2 calls", callCount.get() >= 2);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void internalErrorWithUnknownKindContinuesPolling() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        AtomicInteger callCount = new AtomicInteger(0);
        when(requestor.Poll(any(Selector.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // First call returns response with unknown event which triggers INTERNAL_ERROR (UNKNOWN)
                String unknownEventJson = "{\n" +
                    "  \"events\": [\n" +
                    "    {\n" +
                    "      \"event\": \"unrecognized-event-type\",\n" +
                    "      \"data\": {}\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

                return CompletableFuture.completedFuture(FDv2Requestor.FDv2PayloadResponse.success(
                    com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(unknownEventJson),
                    okhttp3.Headers.of(),
                    200
                ));
            } else {
                // Subsequent calls succeed
                return CompletableFuture.completedFuture(makeSuccessResponse());
            }
        });

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(50)
            );

            // Wait for multiple polls
            Thread.sleep(250);

            // First result should be interrupted with UNKNOWN error kind
            FDv2SourceResult result1 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(SourceResultType.STATUS, result1.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result1.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.UNKNOWN, result1.getStatus().getErrorInfo().getKind());

            // Second result should be success (polling continued)
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(SourceResultType.CHANGE_SET, result2.getResultType());

            // Verify polling continued after internal error
            assertTrue("Should have made at least 2 calls", callCount.get() >= 2);

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToTrueInSuccessResponse() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        okhttp3.Headers headers = new okhttp3.Headers.Builder()
            .add("x-ld-fd-fallback", "true")
            .build();

        FDv2Requestor.FDv2PayloadResponse response = FDv2Requestor.FDv2PayloadResponse.success(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(
                "{\"events\": [{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}, {\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:payload-1:100)\", \"version\": 100}}]}"
            ),
            headers,
            200
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertEquals(true, result.isFdv1Fallback());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToFalseWhenHeaderNotPresent() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(makeSuccessResponse()));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertEquals(false, result.isFdv1Fallback());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void fdv1FallbackFlagSetToTrueInErrorResponse() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        okhttp3.Headers headers = new okhttp3.Headers.Builder()
            .add("x-ld-fd-fallback", "true")
            .build();

        FDv2Requestor.FDv2PayloadResponse errorResponse =
            FDv2Requestor.FDv2PayloadResponse.failure(503, headers);
        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(errorResponse));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.STATUS, result.getResultType());
            assertEquals(SourceSignal.INTERRUPTED, result.getStatus().getState());
            assertEquals(true, result.isFdv1Fallback());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void environmentIdExtractedFromHeaders() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        okhttp3.Headers headers = new okhttp3.Headers.Builder()
            .add("x-ld-envid", "test-env-789")
            .build();

        FDv2Requestor.FDv2PayloadResponse response = FDv2Requestor.FDv2PayloadResponse.success(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(
                "{\"events\": [{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}, {\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:payload-1:100)\", \"version\": 100}}]}"
            ),
            headers,
            200
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertNotNull(result.getChangeSet());
            assertEquals("test-env-789", result.getChangeSet().getEnvironmentId());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void bothFdv1FallbackAndEnvironmentIdExtractedFromHeaders() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        okhttp3.Headers headers = new okhttp3.Headers.Builder()
            .add("x-ld-fd-fallback", "true")
            .add("x-ld-envid", "test-env-combined")
            .build();

        FDv2Requestor.FDv2PayloadResponse response = FDv2Requestor.FDv2PayloadResponse.success(
            com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(
                "{\"events\": [{\"event\": \"server-intent\", \"data\": {\"payloads\": [{\"id\": \"payload-1\", \"target\": 100, \"intentCode\": \"xfer-full\", \"reason\": \"payload-missing\"}]}}, {\"event\": \"payload-transferred\", \"data\": {\"state\": \"(p:payload-1:100)\", \"version\": 100}}]}"
            ),
            headers,
            200
        );

        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                    requestor,
                    testLogger,
                    selectorSource,
                    executor,
                    Duration.ofMillis(100)
            );

            FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);

            assertNotNull(result);
            assertEquals(SourceResultType.CHANGE_SET, result.getResultType());
            assertEquals(true, result.isFdv1Fallback());
            assertNotNull(result.getChangeSet());
            assertEquals("test-env-combined", result.getChangeSet().getEnvironmentId());

            synchronizer.close();
        } finally {
            executor.shutdown();
        }
    }
}
