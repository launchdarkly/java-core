package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
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

    private FDv2Requestor.FDv2PollingResponse makeSuccessResponse() {
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
            return new FDv2Requestor.FDv2PollingResponse(
                    com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.parseEventsArray(json),
                    okhttp3.Headers.of()
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
        CompletableFuture<FDv2Requestor.FDv2PollingResponse> delayedResponse = new CompletableFuture<>();
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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            synchronizer.shutdown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void nextReturnsImmediatelyWhenResultQueued() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PollingResponse response = makeSuccessResponse();
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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            synchronizer.shutdown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void multipleItemsQueuedReturnedInOrder() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PollingResponse response = makeSuccessResponse();
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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());

            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result2.getResultType());

            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result3);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result3.getResultType());

            synchronizer.shutdown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void shutdownBeforeNextCalled() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PollingResponse response = makeSuccessResponse();
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
            synchronizer.shutdown();

            // next() should return shutdown result
            FDv2SourceResult result = synchronizer.next().get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
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
        CompletableFuture<FDv2Requestor.FDv2PollingResponse> delayedResponse = new CompletableFuture<>();
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
            synchronizer.shutdown();

            // next() should complete with shutdown result
            FDv2SourceResult result = nextFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void shutdownAfterMultipleItemsQueued() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PollingResponse response = makeSuccessResponse();
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
            synchronizer.shutdown();

            // next() can return either queued items or shutdown
            // Just verify we get valid results and eventually shutdown
            boolean gotShutdown = false;
            for (int i = 0; i < 10; i++) {
                FDv2SourceResult result = synchronizer.next().get(1, TimeUnit.SECONDS);
                assertNotNull(result);
                if (result.getResultType() == FDv2SourceResult.ResultType.STATUS &&
                        result.getStatus().getState() == FDv2SourceResult.State.SHUTDOWN) {
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

            synchronizer.shutdown();
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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());

            // Second result should be the error (INTERRUPTED status)
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result2.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result2.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result2.getStatus().getErrorInfo().getKind());

            // Third result should be success again
            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result3);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result3.getResultType());

            // Verify polling continued after error
            assertTrue("Should have at least 2 successful polls", successCount.get() >= 2);

            synchronizer.shutdown();
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

            synchronizer.shutdown();

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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result.getResultType());

            // Verify polling continued after null response
            assertTrue("Should have successful polls after null", successCount.get() >= 1);

            synchronizer.shutdown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void multipleConsumersCanCallNext() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        FDv2Requestor.FDv2PollingResponse response = makeSuccessResponse();
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

            synchronizer.shutdown();
        } finally {
            executor.shutdown();
        }
    }
}
