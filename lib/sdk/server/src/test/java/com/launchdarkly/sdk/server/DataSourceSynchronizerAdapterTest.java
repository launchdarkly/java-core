package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

@SuppressWarnings("javadoc")
public class DataSourceSynchronizerAdapterTest extends BaseTest {

    private final List<AutoCloseable> resourcesToClose = new ArrayList<>();

    @After
    public void tearDown() {
        for (AutoCloseable resource : resourcesToClose) {
            try {
                resource.close();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
        resourcesToClose.clear();
    }

    /**
     * Test that closing the adapter before initialization completes does not leak threads.
     * This is the main test for the bug fix - verifies that cancelling startFuture unblocks the monitoring task.
     */
    @Test
    public void closeBeforeInitializationDoesNotLeakThread() throws Exception {
        CountDownLatch blockInitLatch = new CountDownLatch(1);
        CountDownLatch futureGetCalledLatch = new CountDownLatch(1);

        // Create an adapter with a data source that blocks during initialization
        // The MockDataSource will signal futureGetCalledLatch when get() is called on the returned future
        DataSourceSynchronizerAdapter adapter = new DataSourceSynchronizerAdapter(sink ->
            new MockDataSource(blockInitLatch, null, null, futureGetCalledLatch)
        );
        resourcesToClose.add(adapter);

        // Start the adapter (launches monitoring task)
        CompletableFuture<FDv2SourceResult> nextFuture = adapter.next();

        // Wait for the monitoring task to actually call get() on the startFuture and block
        // This ensures we're testing the exact scenario: monitoring task is blocked when cancel() is called
        assertTrue("Future.get() should have been called",
                   futureGetCalledLatch.await(1, TimeUnit.SECONDS));

        // Close before initialization completes - this should cancel startFuture and unblock the monitoring task
        adapter.close();

        // Verify next() completes with shutdown result (should be nearly immediate)
        FDv2SourceResult result = nextFuture.get(2, TimeUnit.SECONDS);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());

        // Signal the blocked initialization (should already be cancelled/irrelevant)
        blockInitLatch.countDown();

        // Test passes if we reach here without hanging.
    }

    /**
     * Test that normal initialization (without premature close) still works correctly.
     * This ensures the fix doesn't break the happy path.
     */
    @Test
    public void normalInitializationCompletes() throws Exception {
        CountDownLatch allowInitLatch = new CountDownLatch(1);

        DataSourceSynchronizerAdapter adapter = new DataSourceSynchronizerAdapter(sink ->
            new MockDataSource(allowInitLatch, null)
        );
        resourcesToClose.add(adapter);

        CompletableFuture<FDv2SourceResult> nextFuture = adapter.next();

        // Allow initialization to complete
        allowInitLatch.countDown();

        // Wait briefly for the monitoring task to process completion
        Thread.sleep(200);

        // Close normally
        adapter.close();

        // Verify shutdown result is received
        FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
    }

    /**
     * Test that initialization errors are properly reported.
     * Ensures the exception handling in the monitoring task still works correctly.
     */
    @Test
    public void initializationErrorIsReported() throws Exception {
        // Create an adapter with a data source that fails during initialization
        DataSourceSynchronizerAdapter adapter = new DataSourceSynchronizerAdapter(sink ->
            new MockDataSource(new RuntimeException("Init failed"))
        );
        resourcesToClose.add(adapter);

        CompletableFuture<FDv2SourceResult> nextFuture = adapter.next();

        // Wait for the error to be reported
        FDv2SourceResult result = nextFuture.get(2, TimeUnit.SECONDS);

        // Should receive an interrupted status with error info
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.INTERRUPTED, result.getStatus().getState());
        assertNotNull(result.getStatus().getErrorInfo());
        assertTrue(result.getStatus().getErrorInfo().getMessage().contains("Init failed"));

        adapter.close();
    }

    /**
     * Test that close() can be called before start()/next() without issues.
     */
    @Test
    public void closeBeforeStartDoesNotFail() throws Exception {
        DataSourceSynchronizerAdapter adapter = new DataSourceSynchronizerAdapter(sink ->
            new MockDataSource(new CountDownLatch(1), null)
        );

        // Close before calling next()
        adapter.close();

        // next() should still work and return shutdown immediately
        CompletableFuture<FDv2SourceResult> nextFuture = adapter.next();
        FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
        assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
        assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());
    }

    /**
     * Test multiple rapid close/next cycles to ensure no race conditions.
     */
    @Test
    public void rapidCloseDoesNotCauseIssues() throws Exception {
        for (int i = 0; i < 10; i++) {
            CountDownLatch blockLatch = new CountDownLatch(1);
            DataSourceSynchronizerAdapter adapter = new DataSourceSynchronizerAdapter(sink ->
                new MockDataSource(blockLatch, null)
            );

            CompletableFuture<FDv2SourceResult> nextFuture = adapter.next();
            Thread.sleep(10); // Brief delay to let init start
            adapter.close();

            FDv2SourceResult result = nextFuture.get(1, TimeUnit.SECONDS);
            assertEquals(FDv2SourceResult.ResultType.STATUS, result.getResultType());
            assertEquals(FDv2SourceResult.State.SHUTDOWN, result.getStatus().getState());

            blockLatch.countDown();
        }
    }

    /**
     * Mock DataSource implementation for testing.
     * Allows controlling when initialization completes or fails.
     */
    private static class MockDataSource implements DataSource {
        private final CountDownLatch blockLatch;
        private final CountDownLatch signalLatch;
        private final Exception initException;
        private final CountDownLatch futureGetCalledLatch;
        private final CompletableFuture<Void> startFuture = new CompletableFuture<>();
        private volatile boolean closed = false;

        // Constructor for blocking init
        public MockDataSource(CountDownLatch blockLatch, CountDownLatch signalLatch) {
            this(blockLatch, signalLatch, null, null);
        }

        // Constructor for init that fails
        public MockDataSource(Exception initException) {
            this(null, null, initException, null);
        }

        public MockDataSource(CountDownLatch blockLatch, CountDownLatch signalLatch, Exception initException) {
            this(blockLatch, signalLatch, initException, null);
        }

        public MockDataSource(CountDownLatch blockLatch, CountDownLatch signalLatch, Exception initException, CountDownLatch futureGetCalledLatch) {
            this.blockLatch = blockLatch;
            this.signalLatch = signalLatch;
            this.initException = initException;
            this.futureGetCalledLatch = futureGetCalledLatch;
        }

        @Override
        public Future<Void> start() {
            // Start initialization in background thread
            CompletableFuture.runAsync(() -> {
                try {
                    // Signal that init has started
                    if (signalLatch != null) {
                        signalLatch.countDown();
                    }

                    // If there's an exception to throw, throw it
                    if (initException != null) {
                        startFuture.completeExceptionally(initException);
                        return;
                    }

                    // If there's a latch, wait for it (simulating slow initialization)
                    if (blockLatch != null) {
                        blockLatch.await();
                    }

                    // Complete successfully
                    startFuture.complete(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    startFuture.completeExceptionally(e);
                }
            });

            // If we need to signal when get() is called, wrap the future
            if (futureGetCalledLatch != null) {
                return new SignalingFuture<>(startFuture, futureGetCalledLatch);
            }
            return startFuture;
        }

        @Override
        public void close() {
            closed = true;
            // Note: Like PollingProcessor and StreamProcessor, we do NOT complete the startFuture here
            // This is what originally caused the thread leak that we're fixing in the adapter
        }

        @Override
        public boolean isInitialized() {
            return startFuture.isDone() && !startFuture.isCompletedExceptionally();
        }
    }

    /**
     * Wrapper around a Future that signals a latch when get() is called.
     * Used to precisely detect when the monitoring task calls get() and blocks.
     */
    private static class SignalingFuture<T> implements Future<T> {
        private final Future<T> delegate;
        private final CountDownLatch getCalledLatch;

        public SignalingFuture(Future<T> delegate, CountDownLatch getCalledLatch) {
            this.delegate = delegate;
            this.getCalledLatch = getCalledLatch;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            getCalledLatch.countDown();
            return delegate.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            getCalledLatch.countDown();
            return delegate.get(timeout, unit);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }
    }
}
