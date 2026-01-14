package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());

            // Shutdown with items still in queue
            synchronizer.shutdown();

            // Can still consume queued items
            FDv2SourceResult result2 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result2.getResultType());

            FDv2SourceResult result3 = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(result3);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result3.getResultType());

            // Eventually should get shutdown result
            FDv2SourceResult shutdownResult = synchronizer.next().get(1, TimeUnit.SECONDS);
            assertNotNull(shutdownResult);
            assertEquals(FDv2SourceResult.ResultType.STATUS, shutdownResult.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, shutdownResult.getStatus().getState());
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
    public void errorInPollingQueuedAsInterrupted() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        // First poll succeeds, second fails
        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(makeSuccessResponse()))
            .thenReturn(CompletableFuture.failedFuture(new IOException("Network error")));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                requestor,
                testLogger,
                selectorSource,
                executor,
                Duration.ofMillis(100)
            );

            // First result should be success
            FDv2SourceResult result1 = synchronizer.next().get(5, TimeUnit.SECONDS);
            assertNotNull(result1);
            assertEquals(FDv2SourceResult.ResultType.CHANGE_SET, result1.getResultType());

            // Second result should be interrupted error
            FDv2SourceResult result2 = synchronizer.next().get(5, TimeUnit.SECONDS);
            assertNotNull(result2);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result2.getResultType());
            assertEquals(FDv2SourceResult.State.INTERRUPTED, result2.getStatus().getState());
            assertEquals(DataSourceStatusProvider.ErrorKind.NETWORK_ERROR, result2.getStatus().getErrorInfo().getKind());

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
    public void nullResponseHandledCorrectly() throws Exception {
        FDv2Requestor requestor = mockRequestor();
        SelectorSource selectorSource = mockSelectorSource();
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        // Return null (304 Not Modified)
        when(requestor.Poll(any(Selector.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        try {
            PollingSynchronizerImpl synchronizer = new PollingSynchronizerImpl(
                requestor,
                testLogger,
                selectorSource,
                executor,
                Duration.ofMillis(100)
            );

            // Wait for poll to complete
            Thread.sleep(200);

            // The null response should result in terminal error (unexpected end of response)
            FDv2SourceResult result = synchronizer.next().get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.TERMINAL_ERROR, result.getStatus().getState());

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
