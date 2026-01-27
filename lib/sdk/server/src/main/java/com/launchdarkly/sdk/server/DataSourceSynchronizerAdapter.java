package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Adapter that wraps a DataSource (FDv1 protocol) and exposes it as a Synchronizer (FDv2 protocol).
 * <p>
 * This adapter bridges the push-based DataSource interface with the pull-based Synchronizer interface
 * by intercepting updates through a custom DataSourceUpdateSink and queueing them as FDv2SourceResult objects.
 * <p>
 * The adapter is constructed with a factory function that receives the intercepting update sink and
 * creates the DataSource. This ensures the DataSource uses the adapter's internal sink without exposing it.
 */
class DataSourceSynchronizerAdapter implements Synchronizer {
    private final DataSource dataSource;
    private final IterableAsyncQueue<FDv2SourceResult> resultQueue = new IterableAsyncQueue<>();
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final Object startLock = new Object();
    private boolean started = false;
    private boolean closed = false;
    private Future<Void> startFuture;

    /**
     * Functional interface for creating a DataSource with a given update sink.
     */
    @FunctionalInterface
    public interface DataSourceFactory {
        DataSource create(DataSourceUpdateSink updateSink);
    }

    /**
     * Creates a new adapter that wraps a DataSource.
     *
     * @param dataSourceFactory factory that creates the DataSource with the provided update sink
     * @param originalUpdateSink the original update sink to delegate to
     */
    public DataSourceSynchronizerAdapter(DataSourceFactory dataSourceFactory, DataSourceUpdateSink originalUpdateSink) {
        InterceptingUpdateSink interceptingSink = new InterceptingUpdateSink(originalUpdateSink, resultQueue);
        this.dataSource = dataSourceFactory.create(interceptingSink);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        synchronized (startLock) {
            if (!started && !closed) {
                started = true;
                startFuture = dataSource.start();

                // Monitor the start future for errors
                // The data source will emit updates through the intercepting sink
                CompletableFuture.runAsync(() -> {
                    try {
                        startFuture.get();
                    } catch (ExecutionException e) {
                        // Initialization failed - emit an interrupted status
                        DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                                DataSourceStatusProvider.ErrorKind.UNKNOWN,
                                0,
                                e.getCause() != null ? e.getCause().toString() : e.toString(),
                                Instant.now()
                        );
                        resultQueue.put(FDv2SourceResult.interrupted(errorInfo, false));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close() throws IOException {
        synchronized (startLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        dataSource.close();
        shutdownFuture.complete(FDv2SourceResult.shutdown());
    }

    /**
     * An intercepting DataSourceUpdateSink that converts DataSource updates into FDv2SourceResult objects.
     */
    private static class InterceptingUpdateSink implements DataSourceUpdateSink {
        private final DataSourceUpdateSink delegate;
        private final IterableAsyncQueue<FDv2SourceResult> resultQueue;

        public InterceptingUpdateSink(DataSourceUpdateSink delegate, IterableAsyncQueue<FDv2SourceResult> resultQueue) {
            this.delegate = delegate;
            this.resultQueue = resultQueue;
        }

        @Override
        public boolean init(DataStoreTypes.FullDataSet<DataStoreTypes.ItemDescriptor> allData) {
            boolean success = delegate.init(allData);
            if (success) {
                // Convert the full data set into a ChangeSet and emit it
                DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet =
                        new DataStoreTypes.ChangeSet<>(
                                DataStoreTypes.ChangeSetType.Full,
                                Selector.EMPTY,
                                allData.getData(),
                                null);
                resultQueue.put(FDv2SourceResult.changeSet(changeSet, false));
            }
            return success;
        }

        @Override
        public boolean upsert(DataStoreTypes.DataKind kind, String key, DataStoreTypes.ItemDescriptor item) {
            boolean success = delegate.upsert(kind, key, item);
            if (success) {
                // Convert the upsert into a ChangeSet with a single item and emit it
                DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> items =
                        new DataStoreTypes.KeyedItems<>(Collections.singletonList(
                                Map.entry(key, item)));
                Iterable<Map.Entry<DataStoreTypes.DataKind, DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor>>> data =
                        Collections.singletonList(Map.entry(kind, items));

                DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet =
                        new DataStoreTypes.ChangeSet<>(
                                DataStoreTypes.ChangeSetType.Partial,
                                Selector.EMPTY,
                                data,
                                null);
                resultQueue.put(FDv2SourceResult.changeSet(changeSet, false));
            }
            return success;
        }

        @Override
        public DataStoreStatusProvider getDataStoreStatusProvider() {
            return delegate.getDataStoreStatusProvider();
        }

        @Override
        public void updateStatus(DataSourceStatusProvider.State newState, DataSourceStatusProvider.ErrorInfo newError) {
            delegate.updateStatus(newState, newError);

            // Convert state changes to FDv2SourceResult status events
            switch (newState) {
                case INTERRUPTED:
                    resultQueue.put(FDv2SourceResult.interrupted(newError, false));
                    break;
                case OFF:
                    if (newError != null) {
                        resultQueue.put(FDv2SourceResult.terminalError(newError, false));
                    }
                    break;
                case VALID:
                case INITIALIZING:
                    // These states don't map to FDv2SourceResult status events
                    break;
            }
        }
    }
}
