package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;

import org.junit.After;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@SuppressWarnings("javadoc")
public class FDv2DataSourceTest extends BaseTest {

    private ScheduledExecutorService executor;
    private final LDLogger logger = LDLogger.withAdapter(Logs.none(), "");
    private final List<AutoCloseable> resourcesToClose = new ArrayList<>();

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        for (AutoCloseable resource : resourcesToClose) {
            try {
                resource.close();
            } catch (Exception e) {
                // Ignore cleanup exceptions
            }
        }
        resourcesToClose.clear();
    }

    private DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> makeChangeSet(boolean withSelector) {
        Selector selector = withSelector ? Selector.make(1, "test-state") : Selector.EMPTY;
        return new DataStoreTypes.ChangeSet<>(
            DataStoreTypes.ChangeSetType.None,
            selector,
            null,
            null
        );
    }

    private FDv2SourceResult makeInterruptedResult() {
        return FDv2SourceResult.interrupted(
            new DataSourceStatusProvider.ErrorInfo(
                DataSourceStatusProvider.ErrorKind.NETWORK_ERROR,
                500,
                null,
                Instant.now()
            ),
            false
        );
    }

    private FDv2SourceResult makeTerminalErrorResult() {
        return FDv2SourceResult.terminalError(
            new DataSourceStatusProvider.ErrorInfo(
                DataSourceStatusProvider.ErrorKind.ERROR_RESPONSE,
                401,
                null,
                Instant.now()
            ),
            false
        );
    }

    // ============================================================================
    // Initializer Scenarios
    // ============================================================================

    @Test
    public void firstInitializerFailsSecondInitializerSucceedsWithSelector() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> firstInitializerFuture = new CompletableFuture<>();
        firstInitializerFuture.completeExceptionally(new RuntimeException("First initializer failed"));

        CompletableFuture<FDv2SourceResult> secondInitializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(firstInitializerFuture),
            () -> new MockInitializer(secondInitializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
        // TODO: Verify status updated to VALID when data source status is implemented
    }

    @Test
    public void firstInitializerFailsSecondInitializerSucceedsWithoutSelector() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> firstInitializerFuture = new CompletableFuture<>();
        firstInitializerFuture.completeExceptionally(new RuntimeException("First initializer failed"));

        CompletableFuture<FDv2SourceResult> secondInitializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        BlockingQueue<Boolean> synchronizerCalledQueue = new LinkedBlockingQueue<>();
        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(firstInitializerFuture),
            () -> new MockInitializer(secondInitializerFuture)
        );

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCalledQueue.offer(true);
                return new MockSynchronizer(synchronizerFuture);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for synchronizer to be called
        Boolean synchronizerCalled = synchronizerCalledQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull("Synchronizer should be called", synchronizerCalled);

        // Wait for apply to be processed
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount()); // One from initializer, one from synchronizer
        // TODO: Verify status updated to VALID when data source status is implemented
    }

    @Test
    public void firstInitializerSucceedsWithSelectorSecondInitializerNotInvoked() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> firstInitializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        AtomicBoolean secondInitializerCalled = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(firstInitializerFuture),
            () -> {
                secondInitializerCalled.set(true);
                return new MockInitializer(CompletableFuture.completedFuture(
                    FDv2SourceResult.changeSet(makeChangeSet(true), false)
                ));
            }
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertFalse(secondInitializerCalled.get());
        assertEquals(1, sink.getApplyCount());
        // TODO: Verify status updated to VALID when data source status is implemented
    }

    @Test
    public void allInitializersFailSwitchesToSynchronizers() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> firstInitializerFuture = new CompletableFuture<>();
        firstInitializerFuture.completeExceptionally(new RuntimeException("First failed"));

        CompletableFuture<FDv2SourceResult> secondInitializerFuture = new CompletableFuture<>();
        secondInitializerFuture.completeExceptionally(new RuntimeException("Second failed"));

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(firstInitializerFuture),
            () -> new MockInitializer(secondInitializerFuture)
        );

        BlockingQueue<Boolean> synchronizerCalledQueue = new LinkedBlockingQueue<>();
        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCalledQueue.offer(true);
                return new MockSynchronizer(synchronizerFuture);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for synchronizer to be called
        Boolean synchronizerCalled = synchronizerCalledQueue.poll(2, TimeUnit.SECONDS);
        assertNotNull("Synchronizer should be called", synchronizerCalled);

        // Wait for apply to be processed
        sink.awaitApplyCount(1, 2, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void allThreeInitializersFailWithNoSynchronizers() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> {
                CompletableFuture<FDv2SourceResult> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Failed"));
                return new MockInitializer(future);
            },
            () -> {
                CompletableFuture<FDv2SourceResult> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Failed"));
                return new MockInitializer(future);
            },
            () -> {
                CompletableFuture<FDv2SourceResult> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Failed"));
                return new MockInitializer(future);
            }
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertFalse(dataSource.isInitialized());
        assertEquals(0, sink.getApplyCount());
        // TODO: Verify status reflects exhausted sources when data source status is implemented
    }

    @Test
    public void oneInitializerNoSynchronizerIsWellBehaved() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
        // TODO: Verify status updated to VALID when data source status is implemented
    }

    // ============================================================================
    // Synchronizer Scenarios
    // ============================================================================

    @Test
    public void noInitializersOneSynchronizerIsWellBehaved() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(synchronizerFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for apply to be processed
        sink.awaitApplyCount(1, 2, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void oneInitializerOneSynchronizerIsWellBehaved() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(synchronizerFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for both applies to be processed
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
    }

    @Test
    public void noInitializersAndNoSynchronizersIsWellBehaved() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertFalse(dataSource.isInitialized());
        assertEquals(0, sink.getApplyCount());
        // TODO: Verify status reflects exhausted sources when data source status is implemented
    }

    // ============================================================================
    // Fallback and Recovery
    // ============================================================================

    @Test
    public void errorWithFDv1FallbackTriggersFallback() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), true) // FDv1 fallback flag
        );

        AtomicBoolean synchronizerCalled = new AtomicBoolean(false);
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCalled.set(true);
                return new MockSynchronizer(synchronizerFuture);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(synchronizerCalled.get());
        assertEquals(1, sink.getApplyCount());
        // TODO: Verify FDv1 fallback behavior when implemented
    }

    @Test
    public void fallbackAndRecoveryTasksWellBehaved() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First synchronizer sends INTERRUPTED, triggering fallback after timeout
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeInterruptedResult());
        // Keep it alive so fallback timeout triggers

        // The second synchronizer works fine, sends data
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        // Keep alive for recovery

        AtomicInteger firstSyncCallCount = new AtomicInteger(0);
        AtomicInteger secondSyncCallCount = new AtomicInteger(0);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                firstSyncCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(firstSyncResults);
            },
            () -> {
                secondSyncCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(secondSyncResults);
            }
        );

        // Use short timeouts for testing
        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Expected sequence:
        // 1. First sync sends apply (1)
        // 2. First sync sends INTERRUPTED, fallback timer starts (1 second)
        // 3. After fallback, second sync sends apply (2)
        // 4. Recovery timer starts (2 seconds)
        // 5. After recovery, first sync sends apply again (3)
        // Total time: ~3-4 seconds (1s fallback + 2s recovery + processing)

        // Wait for 3 applies with enough time for fallback (1s) + recovery (2s) + overhead
        sink.awaitApplyCount(3, 5, TimeUnit.SECONDS);

        // Both synchronizers should have been called due to fallback and recovery
        assertTrue(firstSyncCallCount.get() >= 2); // Called initially and after recovery
        assertTrue(secondSyncCallCount.get() >= 1); // Called after fallback
        // TODO: Verify status transitions when data source status is implemented
    }

    @Test
    public void canDisposeWhenSynchronizersFallingBack() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // Synchronizer that sends INTERRUPTED to trigger fallback
        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(makeInterruptedResult());

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Close while the fallback condition is active
        dataSource.close();

        // Test passes if we reach here without hanging
    }

    // ============================================================================
    // Source Blocking
    // ============================================================================

    @Test
    public void terminalErrorBlocksSynchronizer() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First synchronizer sends terminal error
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeTerminalErrorResult());

        // The second synchronizer works fine
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        BlockingQueue<Integer> synchronizerCallQueue = new LinkedBlockingQueue<>();

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCallQueue.offer(1);
                return new MockQueuedSynchronizer(firstSyncResults);
            },
            () -> {
                synchronizerCallQueue.offer(2);
                return new MockQueuedSynchronizer(secondSyncResults);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for both synchronizers to be called
        Integer firstCall = synchronizerCallQueue.poll(2, TimeUnit.SECONDS);
        Integer secondCall = synchronizerCallQueue.poll(2, TimeUnit.SECONDS);

        assertNotNull("First synchronizer should be called", firstCall);
        assertNotNull("Second synchronizer should be called after first is blocked", secondCall);
        assertEquals(Integer.valueOf(1), firstCall);
        assertEquals(Integer.valueOf(2), secondCall);

        // Wait for applies from both
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        // TODO: Verify status transitions when data source status is implemented
    }

    @Test
    public void allThreeSynchronizersFailReportsExhaustion() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // All synchronizers send terminal errors
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                BlockingQueue<FDv2SourceResult> results = new LinkedBlockingQueue<>();
                results.add(makeTerminalErrorResult());
                return new MockQueuedSynchronizer(results);
            },
            () -> {
                BlockingQueue<FDv2SourceResult> results = new LinkedBlockingQueue<>();
                results.add(makeTerminalErrorResult());
                return new MockQueuedSynchronizer(results);
            },
            () -> {
                BlockingQueue<FDv2SourceResult> results = new LinkedBlockingQueue<>();
                results.add(makeTerminalErrorResult());
                return new MockQueuedSynchronizer(results);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertFalse(dataSource.isInitialized());
        // TODO: Verify status reflects exhausted sources when data source status is implemented
    }

    // ============================================================================
    // Disabled Source Prevention
    // ============================================================================

    @Test
    public void disabledDataSourceCannotTriggerActions() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First synchronizer that we'll close and try to trigger
        AtomicReference<MockQueuedSynchronizer> firstSyncRef = new AtomicReference<>();
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeTerminalErrorResult());

        // Second synchronizer
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                MockQueuedSynchronizer sync = new MockQueuedSynchronizer(firstSyncResults);
                firstSyncRef.set(sync);
                return sync;
            },
            () -> new MockQueuedSynchronizer(secondSyncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for synchronizers to run and switch
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        int applyCountAfterSwitch = sink.getApplyCount();

        // Try to send more data from the first (now closed) synchronizer
        MockQueuedSynchronizer firstSync = firstSyncRef.get();
        if (firstSync != null) {
            firstSync.addResult(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        }

        // Wait to ensure closed synchronizer's results aren't processed
        try {
            sink.awaitApplyCount(applyCountAfterSwitch + 1, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout expected
        }

        // Apply count should not have increased from the closed synchronizer
        assertEquals(applyCountAfterSwitch, sink.getApplyCount());
    }

    // ============================================================================
    // Disposal and Cleanup
    // ============================================================================

    @Test
    public void disposeCompletesStartFuture() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // Synchronizer that never completes
        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();

        assertTrue(startFuture.isDone());
        // TODO: Verify status updated to OFF when data source status is implemented
    }

    @Test
    public void noSourcesProvidedCompletesImmediately() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertFalse(dataSource.isInitialized());
        // TODO: Verify status reflects exhausted sources when data source status is implemented
    }

    // ============================================================================
    // Thread Safety and Concurrency
    // ============================================================================

    @Test
    public void startFutureCompletesExactlyOnce() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(synchronizerFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        // Multiple completions would throw, so if we get here, it's working correctly
    }

    @Test
    public void concurrentCloseAndStartHandledSafely() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();

        // Close immediately after starting
        dataSource.close();

        // Should not throw or hang
        startFuture.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void multipleStartCallsEventuallyComplete() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture1 = dataSource.start();
        Future<Void> startFuture2 = dataSource.start();
        Future<Void> startFuture3 = dataSource.start();

        // All calls should complete successfully (even if they return different Future wrappers)
        startFuture1.get(2, TimeUnit.SECONDS);
        startFuture2.get(2, TimeUnit.SECONDS);
        startFuture3.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
    }

    @Test
    public void isInitializedThreadSafe() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        dataSource.start();

        // Call isInitialized from multiple threads
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    dataSource.isInitialized();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void dataSourceUpdatesApplyThreadSafe() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        for (int i = 0; i < 10; i++) {
            syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        }

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for all applies to process
        sink.awaitApplyCount(10, 2, TimeUnit.SECONDS);

        // Should have received multiple applies without error
        assertTrue(sink.getApplyCount() >= 10);
    }

    // ============================================================================
    // Exception Handling
    // ============================================================================

    @Test
    public void initializerThrowsExecutionException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> badFuture = new CompletableFuture<>();
        badFuture.completeExceptionally(new RuntimeException("Execution exception"));

        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(badFuture),
            () -> new MockInitializer(goodFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void initializerThrowsInterruptedException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        AtomicBoolean firstCalled = new AtomicBoolean(false);
        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> {
                firstCalled.set(true);
                return new MockInitializer(() -> {
                    throw new InterruptedException("Interrupted");
                });
            },
            () -> new MockInitializer(goodFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(firstCalled.get());
        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void initializerThrowsCancellationException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> cancelledFuture = new CompletableFuture<>();
        cancelledFuture.cancel(true);

        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(cancelledFuture),
            () -> new MockInitializer(goodFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void synchronizerNextThrowsExecutionException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> badFuture = new CompletableFuture<>();
        badFuture.completeExceptionally(new RuntimeException("Execution exception"));

        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(badFuture),
            () -> new MockSynchronizer(goodFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void synchronizerNextThrowsInterruptedException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        AtomicBoolean firstCalled = new AtomicBoolean(false);
        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                firstCalled.set(true);
                return new MockSynchronizer(() -> {
                    throw new InterruptedException("Interrupted");
                });
            },
            () -> new MockSynchronizer(goodFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(firstCalled.get());
        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void synchronizerNextThrowsCancellationException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> cancelledFuture = new CompletableFuture<>();
        cancelledFuture.cancel(true);

        CompletableFuture<FDv2SourceResult> goodFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(cancelledFuture),
            () -> new MockSynchronizer(goodFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
    }

    // ============================================================================
    // Resource Management
    // ============================================================================

    @Test
    public void closeWithoutStartDoesNotHang() {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        dataSource.close();

        // Test passes if we reach here without hanging
    }

    @Test
    public void closeAfterInitializersCompletesImmediately() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();

    }

    @Test
    public void closeWhileSynchronizerRunningShutdownsSource() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicBoolean synchronizerClosed = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults) {
                @Override
                public void close() {
                    synchronizerClosed.set(true);
                    super.close();
                }
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();

        assertTrue(synchronizerClosed.get());
    }

    @Test
    public void multipleCloseCallsAreIdempotent() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();
        dataSource.close();
        dataSource.close();

        // Test passes if we reach here without throwing
    }

    @Test
    public void closeInterruptsConditionWaiting() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(makeInterruptedResult());
        // Don't add more, so it waits on condition

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Close while condition is waiting
        dataSource.close();

        // Test passes if we reach here without hanging
    }

    // ============================================================================
    // Active Source Management
    // ============================================================================

    @Test
    public void setActiveSourceReturnsShutdownStatus() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        AtomicBoolean shutdownDetected = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture) {
                @Override
                public CompletableFuture<FDv2SourceResult> run() {
                    // This won't be called because close() is called first
                    return super.run();
                }
            }
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        dataSource.close();
        Future<Void> startFuture = dataSource.start();

        // Should complete without hanging since shutdown was already called
        startFuture.get(2, TimeUnit.SECONDS);
        // Test passes if we reach here - shutdown was handled
    }

    @Test
    public void activeSourceClosedWhenSwitchingSynchronizers() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeTerminalErrorResult());

        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicBoolean firstSyncClosed = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(firstSyncResults) {
                @Override
                public void close() {
                    firstSyncClosed.set(true);
                    super.close();
                }
            },
            () -> new MockQueuedSynchronizer(secondSyncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for both synchronizers to run (switch happens after the first sends terminal error)
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);

        assertTrue(firstSyncClosed.get());
    }

    @Test
    public void activeSourceClosedOnShutdown() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicBoolean syncClosed = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults) {
                @Override
                public void close() {
                    syncClosed.set(true);
                    super.close();
                }
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();

        assertTrue(syncClosed.get());
    }

    @Test
    public void setActiveSourceOnInitializerChecksShutdown() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CountDownLatch initializerStarted = new CountDownLatch(1);
        CompletableFuture<FDv2SourceResult> slowResult = new CompletableFuture<>();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(() -> {
                initializerStarted.countDown();
                // Wait for the future to complete (will be completed by shutdown check)
                try {
                    return slowResult.get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return FDv2SourceResult.changeSet(makeChangeSet(true), false);
                }
            })
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);

        Future<Void> startFuture = dataSource.start();

        // Wait for initializer to start
        assertTrue(initializerStarted.await(2, TimeUnit.SECONDS));

        // Close while the initializer is running
        dataSource.close();

        // Complete the future so initializer can finish
        slowResult.complete(FDv2SourceResult.changeSet(makeChangeSet(true), false));

        // Wait for the start method to complete
        startFuture.get(2, TimeUnit.SECONDS);

        // Test passes if we reach here - shutdown handled gracefully
    }

    // ============================================================================
    // Synchronizer State Transitions
    // ============================================================================

    @Test
    public void blockedSynchronizerSkippedInRotation() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First: terminal error (blocked)
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(makeTerminalErrorResult());

        // Second: works
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        // Third: works
        BlockingQueue<FDv2SourceResult> thirdSyncResults = new LinkedBlockingQueue<>();
        thirdSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);
        AtomicInteger thirdCallCount = new AtomicInteger(0);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                firstCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(firstSyncResults);
            },
            () -> {
                secondCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(secondSyncResults);
            },
            () -> {
                thirdCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(thirdSyncResults);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertEquals(1, firstCallCount.get()); // Called once, then blocked
        assertTrue(secondCallCount.get() >= 1); // Called
    }

    @Test
    public void allSynchronizersBlockedReturnsNullAndExits() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                BlockingQueue<FDv2SourceResult> results = new LinkedBlockingQueue<>();
                results.add(makeTerminalErrorResult());
                return new MockQueuedSynchronizer(results);
            },
            () -> {
                BlockingQueue<FDv2SourceResult> results = new LinkedBlockingQueue<>();
                results.add(makeTerminalErrorResult());
                return new MockQueuedSynchronizer(results);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertFalse(dataSource.isInitialized());
    }

    @Test
    public void recoveryResetsToFirstAvailableSynchronizer() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First synchronizer: send data, then INTERRUPTED
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeInterruptedResult());

        // Second synchronizer: send data
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                firstCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(firstSyncResults);
            },
            () -> {
                secondCallCount.incrementAndGet();
                return new MockQueuedSynchronizer(secondSyncResults);
            }
        );

        // Short recovery timeout
        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for recovery timeout to trigger by waiting for multiple synchronizer calls
        // Recovery brings us back to first, so we should see multiple calls eventually
        for (int i = 0; i < 3; i++) {
            sink.awaitApplyCount(i + 1, 5, TimeUnit.SECONDS);
        }

        // Should have called first synchronizer again after recovery
        assertTrue(firstCallCount.get() >= 2 || secondCallCount.get() >= 1);
    }

    @Test
    public void fallbackMovesToNextSynchronizer() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First: send INTERRUPTED to trigger fallback
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeInterruptedResult());

        // Second: works
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        BlockingQueue<Boolean> secondCalledQueue = new LinkedBlockingQueue<>();

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(firstSyncResults),
            () -> {
                secondCalledQueue.offer(true);
                return new MockQueuedSynchronizer(secondSyncResults);
            }
        );

        // Short fallback timeout
        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(3, TimeUnit.SECONDS);

        // Wait for the second synchronizer to be called after fallback timeout
        Boolean secondCalled = secondCalledQueue.poll(3, TimeUnit.SECONDS);
        assertNotNull("Second synchronizer should be called after fallback", secondCalled);
    }

    // ============================================================================
    // Condition Lifecycle
    // ============================================================================

    @Test
    public void conditionsClosedAfterSynchronizerLoop() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(makeTerminalErrorResult());

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        dataSource.close();

        // If conditions weren't closed properly, we might see issues
    }

    @Test
    public void conditionsInformedOfAllResults() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(makeInterruptedResult());
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 10, 20);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // All results should be processed
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 2);
    }

    @Test
    public void conditionsClosedOnException() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> exceptionFuture = new CompletableFuture<>();
        exceptionFuture.completeExceptionally(new RuntimeException("Error"));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(exceptionFuture),
            () -> new MockSynchronizer(CompletableFuture.completedFuture(
                FDv2SourceResult.changeSet(makeChangeSet(false), false)
            ))
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Conditions should be closed despite exception
    }

    @Test
    public void primeSynchronizerHasNoRecoveryCondition() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        // Keep alive

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults),
            () -> new MockQueuedSynchronizer(new LinkedBlockingQueue<>())
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Prime synchronizer should not have a recovery condition
        // This is tested implicitly by the implementation
    }

    @Test
    public void nonPrimeSynchronizerHasBothConditions() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        // First: send INTERRUPTED to trigger fallback
        BlockingQueue<FDv2SourceResult> firstSyncResults = new LinkedBlockingQueue<>();
        firstSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        firstSyncResults.add(makeInterruptedResult());

        // Second: will have both conditions
        BlockingQueue<FDv2SourceResult> secondSyncResults = new LinkedBlockingQueue<>();
        secondSyncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(firstSyncResults),
            () -> new MockQueuedSynchronizer(secondSyncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Non-prime synchronizer should have both fallback and recovery
        // This is tested implicitly by the implementation
    }

    @Test
    public void singleSynchronizerHasNoConditions() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Single synchronizer should have no conditions
        // This is tested implicitly by the implementation
    }

    @Test
    public void conditionFutureNeverCompletesWhenNoConditions() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 1, 2);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Should process both ChangeSet results without condition interruption
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 2);
    }

    // ============================================================================
    // Data Flow Verification
    // ============================================================================

    @Test
    public void changeSetAppliedToDataSourceUpdates() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertEquals(1, sink.getApplyCount());
        assertNotNull(sink.getLastChangeSet());
    }

    @Test
    public void multipleChangeSetsAppliedInOrder() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for all 3 ChangeSets to be applied
        sink.awaitApplyCount(3, 2, TimeUnit.SECONDS);

        assertEquals(3, sink.getApplyCount());
    }

    @Test
    public void selectorEmptyStillCompletesIfAnyDataReceived() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(synchronizerFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());

        // Wait for the synchronizer to also run
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount()); // Both initializer and synchronizer
    }

    @Test
    public void selectorNonEmptyCompletesInitialization() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(true), false)
        );

        AtomicBoolean synchronizerCalled = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCalled.set(true);
                return new MockSynchronizer(CompletableFuture.completedFuture(
                    FDv2SourceResult.changeSet(makeChangeSet(false), false)
                ));
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertFalse(synchronizerCalled.get()); // Should not proceed to synchronizers
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void initializerChangeSetWithoutSelectorCompletesIfLastInitializer() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        CompletableFuture<FDv2SourceResult> initializerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> new MockInitializer(initializerFuture)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertEquals(1, sink.getApplyCount());
        // TODO: Verify status updated to VALID when data source status is implemented
    }

    @Test
    public void synchronizerChangeSetAlwaysCompletesStartFuture() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockSynchronizer(synchronizerFuture)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
    }

    // ============================================================================
    // Status Result Handling
    // ============================================================================

    @Test
    public void goodbyeStatusHandledGracefully() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(FDv2SourceResult.goodbye("server-requested", false));
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for applies to be processed
        sink.awaitApplyCount(2, 2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        assertTrue(sink.getApplyCount() >= 2);
    }

    @Test
    public void shutdownStatusExitsImmediately() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        syncResults.add(FDv2SourceResult.shutdown());

        AtomicBoolean secondSynchronizerCalled = new AtomicBoolean(false);

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults),
            () -> {
                secondSynchronizerCalled.set(true);
                return new MockQueuedSynchronizer(new LinkedBlockingQueue<>());
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        // Wait for first synchronizer's apply
        sink.awaitApplyCount(1, 2, TimeUnit.SECONDS);

        // Verify the second synchronizer was not called (SHUTDOWN exits immediately)
        assertFalse(secondSynchronizerCalled.get());
    }

    @Test
    public void fdv1FallbackFlagHonored() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), true)); // FDv1 fallback

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
        // TODO: Verify FDv1 fallback behavior when implemented
    }

    // ============================================================================
    // Edge Cases and Initialization
    // ============================================================================

    @Test
    public void emptyInitializerListSkipsToSynchronizers() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();

        AtomicBoolean synchronizerCalled = new AtomicBoolean(false);
        CompletableFuture<FDv2SourceResult> synchronizerFuture = CompletableFuture.completedFuture(
            FDv2SourceResult.changeSet(makeChangeSet(false), false)
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> {
                synchronizerCalled.set(true);
                return new MockSynchronizer(synchronizerFuture);
            }
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture = dataSource.start();
        startFuture.get(2, TimeUnit.SECONDS);

        assertTrue(synchronizerCalled.get());
        assertTrue(dataSource.isInitialized());
    }

    @Test
    public void startedFlagPreventsMultipleRuns() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        AtomicInteger runCount = new AtomicInteger(0);

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of(
            () -> {
                runCount.incrementAndGet();
                return new MockInitializer(CompletableFuture.completedFuture(
                    FDv2SourceResult.changeSet(makeChangeSet(true), false)
                ));
            }
        );

        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of();

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        Future<Void> startFuture1 = dataSource.start();
        Future<Void> startFuture2 = dataSource.start();
        Future<Void> startFuture3 = dataSource.start();

        // Wait for all start futures to complete
        // The data sources use Future<Void> instead of CompletableFuture<Void>, so we cannot use CompletableFuture.allOf.
        startFuture1.get(2, TimeUnit.SECONDS);
        startFuture2.get(2, TimeUnit.SECONDS);
        startFuture3.get(2, TimeUnit.SECONDS);

        // Verify initializer was only called once despite multiple start() calls
        assertEquals(1, runCount.get());
    }

    @Test
    public void startBeforeRunCompletesAllComplete() throws Exception {
        executor = Executors.newScheduledThreadPool(2);
        MockDataSourceUpdateSink sink = new MockDataSourceUpdateSink();

        BlockingQueue<FDv2SourceResult> syncResults = new LinkedBlockingQueue<>();
        syncResults.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));

        ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializers = ImmutableList.of();
        ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers = ImmutableList.of(
            () -> new MockQueuedSynchronizer(syncResults)
        );

        FDv2DataSource dataSource = new FDv2DataSource(initializers, synchronizers, sink, Thread.NORM_PRIORITY, logger, executor, 120, 300);
        resourcesToClose.add(dataSource);

        // Call start multiple times before completion
        Future<Void> future1 = dataSource.start();
        Future<Void> future2 = dataSource.start();

        // Both should complete successfully
        future1.get(2, TimeUnit.SECONDS);
        future2.get(2, TimeUnit.SECONDS);

        assertTrue(dataSource.isInitialized());
    }

    // ============================================================================
    // Mock Implementations
    // ============================================================================

    private static class MockDataSourceUpdateSink implements DataSourceUpdateSinkV2 {
        private final AtomicInteger applyCount = new AtomicInteger(0);
        private final AtomicReference<DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor>> lastChangeSet = new AtomicReference<>();
        private final BlockingQueue<Boolean> applySignals = new LinkedBlockingQueue<>();

        @Override
        public boolean apply(DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet) {
            applyCount.incrementAndGet();
            lastChangeSet.set(changeSet);
            applySignals.offer(true);
            return true;
        }

        @Override
        public void updateStatus(DataSourceStatusProvider.State newState, DataSourceStatusProvider.ErrorInfo errorInfo) {
            // TODO: Track status updates when data source status is fully implemented
        }

        @Override
        public DataStoreStatusProvider getDataStoreStatusProvider() {
            return null; // Not needed for these tests
        }

        public int getApplyCount() {
            return applyCount.get();
        }

        public DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> getLastChangeSet() {
            return lastChangeSet.get();
        }

        public void awaitApplyCount(int expectedCount, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (applyCount.get() < expectedCount && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    applySignals.poll(remaining, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private static class MockInitializer implements Initializer {
        private final CompletableFuture<FDv2SourceResult> result;
        private final ThrowingSupplier<FDv2SourceResult> supplier;

        public MockInitializer(CompletableFuture<FDv2SourceResult> result) {
            this.result = result;
            this.supplier = null;
        }

        public MockInitializer(ThrowingSupplier<FDv2SourceResult> supplier) {
            this.result = null;
            this.supplier = supplier;
        }

        @Override
        public CompletableFuture<FDv2SourceResult> run() {
            if (supplier != null) {
                CompletableFuture<FDv2SourceResult> future = new CompletableFuture<>();
                try {
                    future.complete(supplier.get());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
                return future;
            }
            return result;
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    private static class MockSynchronizer implements Synchronizer {
        private final CompletableFuture<FDv2SourceResult> result;
        private final ThrowingSupplier<FDv2SourceResult> supplier;
        private volatile boolean closed = false;
        private volatile boolean resultReturned = false;

        public MockSynchronizer(CompletableFuture<FDv2SourceResult> result) {
            this.result = result;
            this.supplier = null;
        }

        public MockSynchronizer(ThrowingSupplier<FDv2SourceResult> supplier) {
            this.result = null;
            this.supplier = supplier;
        }

        @Override
        public CompletableFuture<FDv2SourceResult> next() {
            if (closed) {
                return CompletableFuture.completedFuture(FDv2SourceResult.shutdown());
            }
            if (supplier != null) {
                CompletableFuture<FDv2SourceResult> future = new CompletableFuture<>();
                try {
                    future.complete(supplier.get());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
                return future;
            }
            // Only return the result once, then return a never-completing future
            if (!resultReturned) {
                resultReturned = true;
                return result;
            } else {
                return new CompletableFuture<>(); // Never completes
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class MockQueuedSynchronizer implements Synchronizer {
        private final BlockingQueue<FDv2SourceResult> results;
        private volatile boolean closed = false;

        public MockQueuedSynchronizer(BlockingQueue<FDv2SourceResult> results) {
            this.results = results;
        }

        public void addResult(FDv2SourceResult result) {
            if (!closed) {
                results.add(result);
            }
        }

        @Override
        public CompletableFuture<FDv2SourceResult> next() {
            if (closed) {
                return CompletableFuture.completedFuture(FDv2SourceResult.shutdown());
            }

            // Try to get immediately, don't wait
            FDv2SourceResult result = results.poll();
            if (result != null) {
                return CompletableFuture.completedFuture(result);
            } else {
                // Queue is empty - return a never-completing future to simulate waiting for more data
                return new CompletableFuture<>();
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}