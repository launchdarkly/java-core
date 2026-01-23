package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.ConditionFactory;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.FallbackCondition;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.RecoveryCondition;

class FDv2DataSource implements DataSource {
    /**
     * Default fallback timeout of 2 minutes. The timeout is only configurable for testing.
     */
    private static final int defaultFallbackTimeout = 2 * 60;

    /**
     * Default recovery timeout of 5 minutes. The timeout is only configurable for testing.
     */
    private static final long defaultRecoveryTimeout = 5 * 60;

    private final List<DataSourceFactory<Initializer>> initializers;
    private final SynchronizerStateManager synchronizerStateManager;

    private final List<ConditionFactory> conditionFactories;

    private final DataSourceUpdateSinkV2 dataSourceUpdates;

    private final CompletableFuture<Boolean> startFuture = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final int threadPriority;

    private final LDLogger logger;

    public interface DataSourceFactory<T> {
        T build();
    }

    public FDv2DataSource(
        ImmutableList<DataSourceFactory<Initializer>> initializers,
        ImmutableList<DataSourceFactory<Synchronizer>> synchronizers,
        DataSourceUpdateSinkV2 dataSourceUpdates,
        int threadPriority,
        LDLogger logger,
        ScheduledExecutorService sharedExecutor
    ) {
        this(initializers,
            synchronizers,
            dataSourceUpdates,
            threadPriority,
            logger,
            sharedExecutor,
            defaultFallbackTimeout,
            defaultRecoveryTimeout
        );
    }


    public FDv2DataSource(
        ImmutableList<DataSourceFactory<Initializer>> initializers,
        ImmutableList<DataSourceFactory<Synchronizer>> synchronizers,
        DataSourceUpdateSinkV2 dataSourceUpdates,
        int threadPriority,
        LDLogger logger,
        ScheduledExecutorService sharedExecutor,
        long fallbackTimeout,
        long recoveryTimeout
    ) {
        this.initializers = initializers;
        List<SynchronizerFactoryWithState> synchronizerFactories = synchronizers
            .stream()
            .map(SynchronizerFactoryWithState::new)
            .collect(Collectors.toList());
        this.synchronizerStateManager = new SynchronizerStateManager(synchronizerFactories);
        this.dataSourceUpdates = dataSourceUpdates;
        this.threadPriority = threadPriority;
        this.logger = logger;
        this.conditionFactories = new ArrayList<>();
        this.conditionFactories.add(new FallbackCondition.Factory(sharedExecutor, fallbackTimeout));
        this.conditionFactories.add(new RecoveryCondition.Factory(sharedExecutor, recoveryTimeout));
    }

    private void run() {
        Thread runThread = new Thread(() -> {
            if (!initializers.isEmpty()) {
                runInitializers();
            }
            boolean fdv1Fallback = runSynchronizers();
            if (fdv1Fallback) {
                // TODO: Run FDv1 fallback.
            }
            // TODO: Handle. We have ran out of sources or we are shutting down.
        });
        runThread.setDaemon(true);
        runThread.setPriority(threadPriority);
        runThread.start();
    }


    private void runInitializers() {
        boolean anyDataReceived = false;
        for (DataSourceFactory<Initializer> factory : initializers) {
            try {
                Initializer initializer = factory.build();
                if (synchronizerStateManager.setActiveSource(initializer)) return;
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
                // TODO: Better messaging?
                // TODO: Data source status?
                logger.warn("Error running initializer: {}", e.toString());
            }
        }
        // We received data without a selector, and we have exhausted initializers, so we are going to
        // consider ourselves initialized.
        if (anyDataReceived) {
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
            startFuture.complete(true);
        }
    }

    /**
     * Determine conditions for the current synchronizer. Synchronizers require different conditions depending on if
     * they are the 'prime' synchronizer or if there are other available synchronizers to use.
     * @return a list of conditions to apply to the synchronizer
     */
    private List<Condition> getConditions() {
        int availableSynchronizers = synchronizerStateManager.getAvailableSynchronizerCount();
        boolean isPrimeSynchronizer = synchronizerStateManager.isPrimeSynchronizer();

        if(availableSynchronizers == 1) {
            // If there is only 1 synchronizer, then we cannot fall back or recover, so we don't need any conditions.
            return Collections.emptyList();
        }
        if(isPrimeSynchronizer) {
            // If there isn't a synchronizer to recover to, then don't add and recovery conditions.
            return conditionFactories.stream()
                .filter((ConditionFactory factory) -> factory.getType() != Condition.ConditionType.RECOVERY)
                .map(ConditionFactory::build).collect(Collectors.toList());
        }
        // The synchronizer can both fall back and recover.
        return conditionFactories.stream().map(ConditionFactory::build).collect(Collectors.toList());
    }

    private boolean runSynchronizers() {
        SynchronizerFactoryWithState availableSynchronizer = synchronizerStateManager.getNextAvailableSynchronizer();
        while (availableSynchronizer != null) {
            Synchronizer synchronizer = availableSynchronizer.build();

            // Returns true if shutdown.
            if (synchronizerStateManager.setActiveSource(synchronizer)) return false;

            try {
                boolean running = true;
                // Conditions run once for the life of the synchronizer.
                List<Condition> conditions = getConditions();

                // The conditionsFuture will complete if any condition is met. Meeting any condition means we will
                // switch to a different synchronizer.
                CompletableFuture<Object> conditionsFuture = CompletableFuture.anyOf(
                    conditions.stream().map(Condition::execute).toArray(CompletableFuture[]::new));

                while (running) {
                    CompletableFuture<FDv2SourceResult> nextResultFuture = synchronizer.next();

                    Object res = CompletableFuture.anyOf(conditionsFuture, nextResultFuture).get();

                    if(res instanceof Condition) {
                        Condition c = (Condition) res;
                        switch (c.getType()) {
                            case FALLBACK:
                                // For fallback, we will move to the next available synchronizer, which may loop.
                                // This is the default behavior of exiting the run loop, so we don't need to take
                                // any action.
                                break;
                            case RECOVERY:
                                // For recovery, we will start at the first available synchronizer.
                                // So we reset the source index, and finding the source will start at the beginning.
                                synchronizerStateManager.resetSourceIndex();
                                break;
                        }
                        // A running synchronizer will only have fallback and recovery conditions that it can act on.
                        // So, if there are no synchronizers to recover to or fallback to, then we will not have
                        // those conditions.
                        break;
                    }


                    FDv2SourceResult result = (FDv2SourceResult) res;
                    conditions.forEach(c -> c.inform(result));

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
                                    return false;
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
                    // We have been requested to fall back to FDv1. We handle whatever message was associated,
                    // close the synchronizer, and then fallback.
                    if(result.isFdv1Fallback()) {
                        // When falling back to FDv1, we are done with any FDv2 synchronizers.
                        synchronizerStateManager.shutdown();
                        return true;
                    }
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // TODO: Log.
                // Move to next synchronizer.
            }
            availableSynchronizer = synchronizerStateManager.getNextAvailableSynchronizer();
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
    public void close() {
        // If there is an active source, we will shut it down, and that will result in the loop handling that source
        // exiting.
        // If we do not have an active source, then the loop will check isShutdown when attempting to set one. When
        // it detects shutdown, it will exit the loop.
        synchronizerStateManager.shutdown();

        // If this is already set, then this has no impact.
        startFuture.complete(false);
    }
}
