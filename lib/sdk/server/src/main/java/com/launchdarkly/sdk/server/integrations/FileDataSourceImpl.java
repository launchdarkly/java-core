package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBuilder.SourceInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements taking flag data from files and putting it into the data store, at startup time and
 * optionally whenever files change.
 * <p>
 * This is the legacy DataSource implementation for backward compatibility. It internally uses
 * {@link FileSynchronizer} for file loading and watching, adapting the results to the DataSource API.
 * <p>
 * For FDv2 integration, use {@link FileInitializer} or {@link FileSynchronizer} directly.
 */
final class FileDataSourceImpl implements DataSource {
    private final DataSourceUpdateSink dataSourceUpdates;
    private final FileSynchronizer synchronizer;
    private final AtomicBoolean inited = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LDLogger logger;
    private Thread updateThread;

    FileDataSourceImpl(
        DataSourceUpdateSink dataSourceUpdates,
        List<SourceInfo> sources,
        boolean autoUpdate,
        FileData.DuplicateKeysHandling duplicateKeysHandling,
        LDLogger logger,
        boolean persist
    ) {
        this.dataSourceUpdates = dataSourceUpdates;
        this.logger = logger;
        this.synchronizer = new FileSynchronizer(sources, autoUpdate, duplicateKeysHandling, logger, persist);
    }

    @Override
    public Future<Void> start() {
        final Future<Void> initFuture = CompletableFuture.completedFuture(null);

        // Get initial data from the synchronizer
        FDv2SourceResult initialResult;
        try {
            initialResult = synchronizer.next().get();
        } catch (Exception e) {
            logger.error("Error getting initial file data: {}", LogValues.exceptionSummary(e));
            dataSourceUpdates.updateStatus(State.INTERRUPTED, null);
            return initFuture;
        }

        processResult(initialResult);

        // Start a background thread to listen for file changes
        updateThread = new Thread(this::runUpdateLoop, FileDataSourceImpl.class.getName());
        updateThread.setDaemon(true);
        updateThread.start();


        return initFuture;
    }

    private void runUpdateLoop() {
        while (!closed.get()) {
            try {
                FDv2SourceResult result = synchronizer.next().get();
                if (closed.get()) {
                    break;
                }
                if (result.getResultType() == SourceResultType.STATUS &&
                    result.getStatus().getState() == SourceSignal.SHUTDOWN) {
                    break;
                }
                processResult(result);
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.warn("Unexpected exception in file data update loop: {}", LogValues.exceptionSummary(e));
                }
            }
        }
    }

    private void processResult(FDv2SourceResult result) {
        if (result.getResultType() == SourceResultType.CHANGE_SET) {
            // Convert ChangeSet to FullDataSet for legacy init()
            FullDataSet<ItemDescriptor> fullData = new FullDataSet<>(
                result.getChangeSet().getData(),
                result.getChangeSet().shouldPersist()
            );
            dataSourceUpdates.init(fullData);
            dataSourceUpdates.updateStatus(State.VALID, null);
            inited.set(true);
        } else if (result.getResultType() == SourceResultType.STATUS) {
            // Handle error/status results
            if (result.getStatus().getState() != SourceSignal.SHUTDOWN) {
                dataSourceUpdates.updateStatus(State.INTERRUPTED, result.getStatus().getErrorInfo());
            }
            // No terminal errors/shutdown for this adaptation.
        }
    }

    @Override
    public boolean isInitialized() {
        return inited.get();
    }

    @Override
    public void close() throws IOException {
        closed.set(true);
        synchronizer.close();
        if (updateThread != null) {
            updateThread.interrupt();
        }
    }
}
