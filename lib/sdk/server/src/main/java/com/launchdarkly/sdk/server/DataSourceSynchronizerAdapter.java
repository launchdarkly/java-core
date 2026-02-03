package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.collections.IterableAsyncQueue;
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
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Adapter that wraps a DataSource (FDv1 protocol) and exposes it as a Synchronizer (FDv2 protocol).
 * <p>
 * This adapter bridges the push-based DataSource interface with the pull-based Synchronizer interface
 * by listening to updates through a custom DataSourceUpdateSink and queueing them as FDv2SourceResult objects.
 * <p>
 * The adapter is constructed with a factory function that receives the listening update sink and
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
     */
    public DataSourceSynchronizerAdapter(DataSourceFactory dataSourceFactory) {
        ConvertingUpdateSink convertingSink = new ConvertingUpdateSink(resultQueue);
        this.dataSource = dataSourceFactory.create(convertingSink);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        synchronized (startLock) {
            if (!started && !closed) {
                started = true;
                startFuture = dataSource.start();

                // Monitor the start future for errors
                // The data source will emit updates through the listening sink
                Thread monitorThread = new Thread(() -> {
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
                    } catch (CancellationException e) {
                        // Start future was canceled (during close) - exit cleanly
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                monitorThread.setName("LaunchDarkly-SDK-Server-DataSourceAdapter-Monitor");
                monitorThread.setDaemon(true);
                monitorThread.start();
            }
        }

        return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close()  {
        synchronized (startLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        try {
            dataSource.close();
        } catch (IOException e) {
            // Ignore as we are shutting down.
        }
        shutdownFuture.complete(FDv2SourceResult.shutdown());
        if(startFuture != null) {
            // If the start future is done, this has no effect.
            // If it is not, then this will unblock the code waiting on start.
            startFuture.cancel(true);
        }
    }

    /**
     * A DataSourceUpdateSink that converts DataSource updates into FDv2SourceResult objects.
     * This sink doesn't delegate to any other sink - it exists solely to convert FDv1 updates to FDv2 results.
     */
    private static class ConvertingUpdateSink implements DataSourceUpdateSink {
        private final IterableAsyncQueue<FDv2SourceResult> resultQueue;

        public ConvertingUpdateSink(IterableAsyncQueue<FDv2SourceResult> resultQueue) {
            this.resultQueue = resultQueue;
        }

        @Override
        public boolean init(DataStoreTypes.FullDataSet<DataStoreTypes.ItemDescriptor> allData) {
            // Convert the full data set into a ChangeSet and emit it
            DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet =
                    new DataStoreTypes.ChangeSet<>(
                            DataStoreTypes.ChangeSetType.Full,
                            Selector.EMPTY,
                            allData.getData(),
                            null,
                            allData.shouldPersist()
                        );
            resultQueue.put(FDv2SourceResult.changeSet(changeSet, false));
            return true;
        }

        @Override
        public boolean upsert(DataStoreTypes.DataKind kind, String key, DataStoreTypes.ItemDescriptor item) {
            // Convert the upsert into a ChangeSet with a single item and emit it
            DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> items =
                    new DataStoreTypes.KeyedItems<>(Collections.<Map.Entry<String, DataStoreTypes.ItemDescriptor>>singletonList(
                            new AbstractMap.SimpleEntry<>(key, item)));
            Iterable<Map.Entry<DataStoreTypes.DataKind, DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor>>> data =
                    Collections.<Map.Entry<DataStoreTypes.DataKind, DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor>>>singletonList(
                            new AbstractMap.SimpleEntry<>(kind, items));

            DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet =
                    new DataStoreTypes.ChangeSet<>(
                            DataStoreTypes.ChangeSetType.Partial,
                            Selector.EMPTY,
                            data,
                            null,
                            true // default to true as this adapter is used for adapting FDv1 data sources which are always persistent
                        );
            resultQueue.put(FDv2SourceResult.changeSet(changeSet, false));
            return true;
        }

        @Override
        public DataStoreStatusProvider getDataStoreStatusProvider() {
            // This adapter doesn't use a data store
            return null;
        }

        @Override
        public void updateStatus(DataSourceStatusProvider.State newState, DataSourceStatusProvider.ErrorInfo newError) {
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
