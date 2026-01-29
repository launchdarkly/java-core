package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
    private static final int defaultFallbackTimeoutSeconds = 2 * 60;

    /**
     * Default recovery timeout of 5 minutes. The timeout is only configurable for testing.
     */
    private static final long defaultRecoveryTimeout = 5 * 60;

    private final SourceManager sourceManager;

    private final List<ConditionFactory> conditionFactories;

    private final DataSourceUpdateSinkV2 dataSourceUpdates;

    private final CompletableFuture<Boolean> startFuture = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final int threadPriority;

    private final LDLogger logger;

    private volatile boolean closed = false;

    public interface DataSourceFactory<T> {
        T build();
    }

    public FDv2DataSource(
        ImmutableList<DataSourceFactory<Initializer>> initializers,
        ImmutableList<DataSourceFactory<Synchronizer>> synchronizers,
        DataSourceFactory<Synchronizer> fdv1DataSourceFactory,
        DataSourceUpdateSinkV2 dataSourceUpdates,
        int threadPriority,
        LDLogger logger,
        ScheduledExecutorService sharedExecutor
    ) {
        this(initializers,
            synchronizers,
            fdv1DataSourceFactory,
            dataSourceUpdates,
            threadPriority,
            logger,
            sharedExecutor,
            defaultFallbackTimeoutSeconds,
            defaultRecoveryTimeout
        );
    }


    public FDv2DataSource(
        ImmutableList<DataSourceFactory<Initializer>> initializers,
        ImmutableList<DataSourceFactory<Synchronizer>> synchronizers,
        DataSourceFactory<Synchronizer> fdv1DataSourceFactory,
        DataSourceUpdateSinkV2 dataSourceUpdates,
        int threadPriority,
        LDLogger logger,
        ScheduledExecutorService sharedExecutor,
        long fallbackTimeout,
        long recoveryTimeout
    ) {
        List<SynchronizerFactoryWithState> synchronizerFactories = synchronizers
            .stream()
            .map(SynchronizerFactoryWithState::new)
            .collect(Collectors.toList());

        // If we have a fdv1 data source factory, then add that to the synchronizer factories in a blocked state.
        // If we receive a request to fallback, then we will unblock it and block all other synchronizers.
        if (fdv1DataSourceFactory != null) {
            SynchronizerFactoryWithState wrapped = new SynchronizerFactoryWithState(fdv1DataSourceFactory,
                true);
            wrapped.block();
            synchronizerFactories.add(wrapped);

            // Currently, we only support 1 fdv1 fallback synchronizer, but that limitation is introduced by the
            // configuration.
        }

        this.sourceManager = new SourceManager(synchronizerFactories, initializers);
        this.dataSourceUpdates = dataSourceUpdates;
        this.threadPriority = threadPriority;
        this.logger = logger;
        this.conditionFactories = new ArrayList<>();
        this.conditionFactories.add(new FallbackCondition.Factory(sharedExecutor, fallbackTimeout));
        this.conditionFactories.add(new RecoveryCondition.Factory(sharedExecutor, recoveryTimeout));
    }

    private void run() {
        Thread runThread = new Thread(() -> {
            if (!sourceManager.hasAvailableSources()) {
                // There are not any initializer or synchronizers, so we are at the best state that
                // can be achieved.
                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                startFuture.complete(true);
                return;
            }
            if (sourceManager.hasInitializers()) {
                runInitializers();
            }

            if(!sourceManager.hasAvailableSynchronizers()) {
                // If already completed by the initializers, then this will have no effect.
                if (!isInitialized() && !closed) {
                    // If we were closed, then closing would have handled our terminal update.
                    dataSourceUpdates.updateStatus(
                        DataSourceStatusProvider.State.OFF,
                        // If we were shutdown during initialization, then we don't need to include an error.
                        new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.UNKNOWN,
                            0,
                            "Initializers exhausted and there are no synchronizers",
                            new Date().toInstant())
                    );
                }
                // If already completed has no effect.
                startFuture.complete(false);
                return;
            }

            runSynchronizers();
            // If we had synchronizers, and we ran out of them, then we are off.

            if(!closed) {
                dataSourceUpdates.updateStatus(
                    DataSourceStatusProvider.State.OFF,
                    // If the data source was closed, then we just report we are OFF without an
                    // associated error.
                    new DataSourceStatusProvider.ErrorInfo(
                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        "All data source acquisition methods have been exhausted.",
                        new Date().toInstant())
                );
            }

            // If we had initialized at some point, then the future will already be complete and this will be ignored.
            startFuture.complete(false);
        });
        runThread.setName("LaunchDarkly-SDK-Server-FDv2DataSource");
        runThread.setDaemon(true);
        runThread.setPriority(threadPriority);
        runThread.start();
    }


    private void runInitializers() {
        boolean anyDataReceived = false;
        Initializer initializer = sourceManager.getNextInitializerAndSetActive();
        while(initializer != null) {
            try {
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
                        FDv2SourceResult.Status status = result.getStatus();
                        switch(status.getState()) {
                            case INTERRUPTED:
                            case TERMINAL_ERROR:
                                // The data source updates handler will filter the state during initializing, but this
                                // will make the error information available.
                                dataSourceUpdates.updateStatus(
                                    // While the error was terminal to the individual initializer, it isn't terminal
                                    // to the data source as a whole.
                                    DataSourceStatusProvider.State.INTERRUPTED,
                                    status.getErrorInfo());
                                break;
                            case SHUTDOWN:
                            case GOODBYE:
                                // We don't need to inform anyone of these statuses.
                                logger.debug("Ignoring status {} from initializer", result.getStatus().getState());
                                break;
                        }
                        break;
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // We don't expect these conditions to happen in practice.

                // The data source updates handler will filter the state during initializing, but this
                // will make the error information available.
                dataSourceUpdates.updateStatus(
                    DataSourceStatusProvider.State.INTERRUPTED,
                    new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        new Date().toInstant()));
                logger.warn("Error running initializer: {}", e.toString());
            }
            initializer = sourceManager.getNextInitializerAndSetActive();
        }
        // We received data without a selector, and we have exhausted initializers, so we are going to
        // consider ourselves initialized.
        if (anyDataReceived) {
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
            startFuture.complete(true);
        }
        // If no data was received, then it is possible initialization will complete from synchronizers, so we give
        // them an opportunity to run before reporting any issues.
    }

    /**
     * Determine conditions for the current synchronizer. Synchronizers require different conditions depending on if
     * they are the 'prime' synchronizer or if there are other available synchronizers to use.
     *
     * @return a list of conditions to apply to the synchronizer
     */
    private List<Condition> getConditions() {
        int availableSynchronizers = sourceManager.getAvailableSynchronizerCount();
        boolean isPrimeSynchronizer = sourceManager.isPrimeSynchronizer();

        if (availableSynchronizers == 1) {
            // If there is only 1 synchronizer, then we cannot fall back or recover, so we don't need any conditions.
            return Collections.emptyList();
        }
        if (isPrimeSynchronizer) {
            // If there isn't a synchronizer to recover to, then don't add and recovery conditions.
            return conditionFactories.stream()
                .filter((ConditionFactory factory) -> factory.getType() != Condition.ConditionType.RECOVERY)
                .map(ConditionFactory::build).collect(Collectors.toList());
        }
        // The synchronizer can both fall back and recover.
        return conditionFactories.stream().map(ConditionFactory::build).collect(Collectors.toList());
    }

    private void runSynchronizers() {
        // When runSynchronizers exists, no matter how it exits, the synchronizerStateManager will be closed.
        try {
            Synchronizer synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();

            // We want to continue running synchronizers for as long as any are available.
            while (synchronizer != null) {
                try {
                    boolean running = true;

                    try (Conditions conditions = new Conditions(getConditions())) {
                        while (running) {
                            CompletableFuture<FDv2SourceResult> nextResultFuture = synchronizer.next();

                            // The conditionsFuture will complete if any condition is met. Meeting any condition means we will
                            // switch to a different synchronizer.
                            Object res = CompletableFuture.anyOf(conditions.getFuture(), nextResultFuture).get();

                            if (res instanceof Condition) {
                                Condition c = (Condition) res;
                                switch (c.getType()) {
                                    case FALLBACK:
                                        // For fallback, we will move to the next available synchronizer, which may loop.
                                        // This is the default behavior of exiting the run loop, so we don't need to take
                                        // any action.
                                        logger.debug("A synchronizer has experienced an interruption and we are falling back.");
                                        break;
                                    case RECOVERY:
                                        // For recovery, we will start at the first available synchronizer.
                                        // So we reset the source index, and finding the source will start at the beginning.
                                        sourceManager.resetSourceIndex();
                                        logger.debug("The data source is attempting to recover to a higher priority synchronizer.");
                                        break;
                                }
                                // A running synchronizer will only have fallback and recovery conditions that it can act on.
                                // So, if there are no synchronizers to recover to or fallback to, then we will not have
                                // those conditions.
                                break;
                            }

                            if (!(res instanceof FDv2SourceResult)) {
                                logger.error("Unexpected result type from synchronizer: {}", res.getClass().getName());
                                continue;
                            }

                            FDv2SourceResult result = (FDv2SourceResult) res;
                            conditions.inform(result);

                            switch (result.getResultType()) {
                                case CHANGE_SET:
                                    dataSourceUpdates.apply(result.getChangeSet());
                                    dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                                    // This could have been completed by any data source. But if it has not been completed before
                                    // now, then we complete it.
                                    startFuture.complete(true);
                                    break;
                                case STATUS:
                                    FDv2SourceResult.Status status = result.getStatus();
                                    switch (status.getState()) {
                                        case INTERRUPTED:
                                            // Handled by conditions.
                                            dataSourceUpdates.updateStatus(
                                                DataSourceStatusProvider.State.INTERRUPTED,
                                                status.getErrorInfo());
                                            break;
                                        case SHUTDOWN:
                                            // We should be overall shutting down.
                                            logger.debug("Synchronizer shutdown.");
                                            return;
                                        case TERMINAL_ERROR:
                                            sourceManager.blockCurrentSynchronizer();
                                            running = false;
                                            dataSourceUpdates.updateStatus(
                                                DataSourceStatusProvider.State.INTERRUPTED,
                                                status.getErrorInfo());
                                            break;
                                        case GOODBYE:
                                            // We let the synchronizer handle this internally.
                                            break;
                                    }
                                    break;
                            }
                            // We have been requested to fall back to FDv1. We handle whatever message was associated,
                            // close the synchronizer, and then fallback.
                            // Only trigger fallback if we're not already running the FDv1 fallback synchronizer.
                            if (
                                result.isFdv1Fallback() &&
                                    sourceManager.hasFDv1Fallback() &&
                                    // This shouldn't happen in practice, an FDv1 source shouldn't request fallback
                                    // to FDv1. But if it does, then we will discard its request.
                                    !sourceManager.isCurrentSynchronizerFDv1Fallback()
                            ) {
                                sourceManager.fdv1Fallback();
                                running = false;
                            }
                        }
                    }
                } catch (ExecutionException | InterruptedException | CancellationException e) {
                    dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED,
                        new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.UNKNOWN,
                            0,
                            e.toString(),
                            new Date().toInstant()
                        ));
                    logger.warn("Error running synchronizer: {}, will try next synchronizer, or retry.", e.toString());
                    // Move to the next synchronizer.
                }
                // Get the next available synchronizer and set it active
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            }
        } catch(Exception e) {
            logger.error("Unexpected error in DataSource: {}", e.toString());
        }finally {
            sourceManager.close();
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
    public void close() {
        closed = true;
        // If there is an active source, we will shut it down, and that will result in the loop handling that source
        // exiting.
        // If we do not have an active source, then the loop will check isShutdown when attempting to set one. When
        // it detects shutdown, it will exit the loop.
        sourceManager.close();

        dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.OFF, null);

        // If this is already set, then this has no impact.
        startFuture.complete(false);
    }

    /**
     * Helper class to manage the lifecycle of conditions with automatic cleanup.
     */
    private static class Conditions implements AutoCloseable {
        private final List<Condition> conditions;
        private final CompletableFuture<Object> conditionsFuture;

        public Conditions(List<Condition> conditions) {
            this.conditions = conditions;
            this.conditionsFuture = conditions.isEmpty()
                ? new CompletableFuture<>() // Never completes if no conditions
                : CompletableFuture.anyOf(
                conditions.stream().map(Condition::execute).toArray(CompletableFuture[]::new));
        }

        public CompletableFuture<Object> getFuture() {
            return conditionsFuture;
        }

        public void inform(FDv2SourceResult result) {
            conditions.forEach(c -> c.inform(result));
        }

        @Override
        public void close() {
            conditions.forEach(Condition::close);
        }
    }
}
