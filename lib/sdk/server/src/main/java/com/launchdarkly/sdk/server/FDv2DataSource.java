package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.DataSourceShutdown;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;

import java.io.IOException;
import java.time.Duration;
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

    private final DataSourceUpdateSink dataSourceUpdates;

    private final CompletableFuture<Boolean> startFuture = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final Object activeSourceLock = new Object();
    private DataSourceShutdown activeSource;

    private static class SynchronizerFactoryWithState {
        public enum State {
            /**
             * This synchronizer is available to use.
             */
            Available,

            /**
             * This synchronizer is no longer available to use.
             */
            Blocked,

            /**
             * This synchronizer is recovering from a previous failure and will be available to use
             * after a delay.
             */
            Recovering
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

        public void setRecovering(Duration delay) {
            state = State.Recovering;
            // TODO: Determine how/when to recover.
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
            List<InitializerFactory> initializers,
            List<SynchronizerFactory> synchronizers,
            DataSourceUpdateSink dataSourceUpdates
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

    private void runSynchronizers() {
        SynchronizerFactoryWithState availableSynchronizer = getFirstAvailableSynchronizer();
        // TODO: Add recovery handling. If there are no available synchronizers, but there are
        // recovering ones, then we likely will want to wait for them to be available (or bypass recovery).
        while (availableSynchronizer != null) {
            Synchronizer synchronizer = availableSynchronizer.build();
            try {

                boolean running = true;
                while (running) {
                    FDv2SourceResult result = synchronizer.next().get();
                    switch (result.getResultType()) {
                        case CHANGE_SET:
                            // TODO: Apply to the store.
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
                                case GOODBYE:
                                    running = false;
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

    private void runInitializers() {
        boolean anyDataReceived = false;
        for (InitializerFactory factory : initializers) {
            try {
                Initializer initializer = factory.build();
                synchronized (activeSourceLock) {
                    activeSource = initializer;
                }
                FDv2SourceResult res = initializer.run().get();
                switch (res.getResultType()) {
                    case CHANGE_SET:
                        // TODO: Apply to the store.
                        anyDataReceived = true;
                        if(!res.getChangeSet().getSelector().isEmpty()) {
                            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                            startFuture.complete(true);
                            return;
                        }
                        return;
                    case STATUS:
                        // TODO: Implement.
                        break;
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // TODO: Log.
            }
            // We received data without a selector, and we have exhausted initializers, so we are going to
            // conside ourselves initialized.
            if(anyDataReceived) {
                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                startFuture.complete(true);
            }
        }
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
        // If this is already set, then this has no impact.
        startFuture.complete(false);
        synchronized (synchronizers) {
            for (SynchronizerFactoryWithState synchronizer : synchronizers) {
                synchronizer.block();
            }
        }
        synchronized (activeSourceLock) {
            if (activeSource != null) {
                activeSource.shutdown();
            }
        }
    }
}
