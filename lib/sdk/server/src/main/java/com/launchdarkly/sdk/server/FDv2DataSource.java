package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class FDv2DataSource implements DataSource {
    private final List<InitializerFactory> initializers;
    private final List<SynchronizerFactoryWithState> synchronizers;

    private final DataSourceUpdateSinkV2 dataSourceUpdates;

    private final CompletableFuture<Boolean> startFuture = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Lock for active sources and shutdown state.
     */
    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    private static class SynchronizerFactoryWithState {
        public enum State {
            /**
             * This synchronizer is available to use.
             */
            Available,

            /**
             * This synchronizer is no longer available to use.
             */
            Blocked
        }

        private final SynchronizerFactory factory;

        private State state = State.Available;


        public SynchronizerFactoryWithState(SynchronizerFactory factory) {
            this.factory = factory;
        }

        public State getState() {
            return state;
        }

        public void block() {
            state = State.Blocked;
        }

        public Synchronizer build() {
            return factory.build();
        }
    }

    public interface InitializerFactory {
        Initializer build();
    }

    public interface SynchronizerFactory {
        Synchronizer build();
    }


    public FDv2DataSource(
            ImmutableList<InitializerFactory> initializers,
            ImmutableList<SynchronizerFactory> synchronizers,
            DataSourceUpdateSinkV2 dataSourceUpdates
    ) {
        this.initializers = initializers;
        this.synchronizers = synchronizers
                .stream()
                .map(SynchronizerFactoryWithState::new)
                .collect(Collectors.toList());
        this.dataSourceUpdates = dataSourceUpdates;
    }

    private void run() {
        Thread runThread = new Thread(() -> {
            if (!initializers.isEmpty()) {
                runInitializers();
            }
            runSynchronizers();
            // TODO: Handle. We have ran out of sources or we are shutting down.
        });
        runThread.setDaemon(true);
        // TODO: Thread priority.
        //thread.setPriority(threadPriority);
        runThread.start();
    }

    private SynchronizerFactoryWithState getFirstAvailableSynchronizer() {
        synchronized (synchronizers) {
            for (SynchronizerFactoryWithState synchronizer : synchronizers) {
                if (synchronizer.getState() == SynchronizerFactoryWithState.State.Available) {
                    return synchronizer;
                }
            }

            return null;
        }
    }

    private void runInitializers() {
        boolean anyDataReceived = false;
        for (InitializerFactory factory : initializers) {
            try {
                Initializer initializer = factory.build();
                if (setActiveSource(initializer)) return;
                FDv2SourceResult result = initializer.run().get();
                switch (result.getResultType()) {
                    case CHANGE_SET:
                        dataSourceUpdates.apply(result.getChangeSet());
                        anyDataReceived = true;
                        if (!result.getChangeSet().getSelector().isEmpty()) {
                            // We received data with a selector, so we end the initialization process.
                            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                            startFuture.complete(true);
                            return;
                        }
                        break;
                    case STATUS:
                        // TODO: Implement.
                        break;
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // TODO: Log.
            }
        }
        // We received data without a selector, and we have exhausted initializers, so we are going to
        // consider ourselves initialized.
        if (anyDataReceived) {
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
            startFuture.complete(true);
        }
    }

    private void runSynchronizers() {
        SynchronizerFactoryWithState availableSynchronizer = getFirstAvailableSynchronizer();
        // TODO: Add recovery handling. If there are no available synchronizers, but there are
        // recovering ones, then we likely will want to wait for them to be available (or bypass recovery).
        while (availableSynchronizer != null) {
            Synchronizer synchronizer = availableSynchronizer.build();
            // Returns true if shutdown.
            if (setActiveSource(synchronizer)) return;
            try {
                boolean running = true;
                while (running) {
                    FDv2SourceResult result = synchronizer.next().get();
                    switch (result.getResultType()) {
                        case CHANGE_SET:
                            dataSourceUpdates.apply(result.getChangeSet());
                            // This could have been completed by any data source. But if it has not been completed before
                            // now, then we complete it.
                            startFuture.complete(true);
                            break;
                        case STATUS:
                            FDv2SourceResult.Status status = result.getStatus();
                            switch (status.getState()) {
                                case INTERRUPTED:
                                    // TODO: Track how long we are interrupted.
                                    break;
                                case SHUTDOWN:
                                    // We should be overall shutting down.
                                    // TODO: We may need logging or to do a little more.
                                    return;
                                case TERMINAL_ERROR:
                                    availableSynchronizer.block();
                                    running = false;
                                    break;
                                case GOODBYE:
                                    // We let the synchronizer handle this internally.
                                    break;
                            }
                            break;
                    }
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // TODO: Log.
                // Move to next synchronizer.
            }
            availableSynchronizer = getFirstAvailableSynchronizer();
        }
    }

    private void safeClose(Closeable synchronizer) {
        try {
            synchronizer.close();
        } catch (IOException e) {
            // Ignore close exceptions.
        }
    }

    private boolean setActiveSource(Closeable synchronizer) {
        synchronized (activeSourceLock) {
            if (activeSource != null) {
                safeClose(activeSource);
            }
            if (isShutdown) {
                safeClose(synchronizer);
                return true;
            }
            activeSource = synchronizer;
        }
        return false;
    }

    @Override
    public Future<Void> start() {
        if (!started.getAndSet(true)) {
            run();
        }
        return startFuture.thenApply(x -> null);
    }

    @Override
    public boolean isInitialized() {
        try {
            return startFuture.isDone() && startFuture.get();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        // If there is an active source, we will shut it down, and that will result in the loop handling that source
        // exiting.
        // If we do not have an active source, then the loop will check isShutdown when attempting to set one. When
        // it detects shutdown it will exit the loop.
        synchronized (activeSourceLock) {
            isShutdown = true;
            if (activeSource != null) {
                activeSource.close();
            }
        }

        // If this is already set, then this has no impact.
        startFuture.complete(false);
    }
}
