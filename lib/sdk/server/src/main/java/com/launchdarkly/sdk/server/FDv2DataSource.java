package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.Condition;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.ConditionFactory;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.FallbackCondition;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.RecoveryCondition;
import static com.launchdarkly.sdk.server.FDv2DataSourceConditions.UnparkCondition;

class FDv2DataSource implements DataSource {
    /**
     * Default fallback timeout of 2 minutes. The timeout is only configurable for testing.
     */
    private static final Duration DEFAULT_FALLBACK_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Default recovery timeout of 5 minutes. The timeout is only configurable for testing.
     */
    private static final Duration DEFAULT_RECOVERY_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Default base of the park delay that follows an unexpected failure. The delay is only configurable for testing.
     */
    private static final Duration DEFAULT_PARK_INITIAL_DELAY = Duration.ofMinutes(5);

    /**
     * Default maximum of the park delay. The delay is only configurable for testing.
     */
    private static final Duration DEFAULT_PARK_MAX_DELAY = Duration.ofHours(1);

    /**
     * Default base of the failover delay that follows a park. The delay is only configurable for testing.
     */
    private static final Duration DEFAULT_FAILOVER_DELAY = Duration.ofSeconds(1);

    private final SourceManager sourceManager;

    private final List<ConditionFactory> conditionFactories;

    private final Duration parkInitialDelay;

    private final Duration parkMaxDelay;

    private final Duration failoverDelay;

    private final DataSourceUpdateSinkV2 dataSourceUpdates;

    private final CompletableFuture<Boolean> startFuture = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final int threadPriority;

    private final LDLogger logger;

    private volatile boolean closed = false;

    /**
     * Completes when the data source is closed.
     */
    private final CompletableFuture<Void> closedSignal = new CompletableFuture<>();

    /**
     * Avoid duplicate orchestration logs for the same synchronizer and {@link SourceSignal}.
     */
    private String lastLoggedSynchronizerDedupeName;

    private SourceSignal lastLoggedSynchronizerDedupeStatus;

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
            DEFAULT_FALLBACK_TIMEOUT,
            DEFAULT_RECOVERY_TIMEOUT,
            DEFAULT_PARK_INITIAL_DELAY,
            DEFAULT_PARK_MAX_DELAY,
            DEFAULT_FAILOVER_DELAY
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
        Duration fallbackTimeout,
        Duration recoveryTimeout,
        Duration parkInitialDelay,
        Duration parkMaxDelay,
        Duration failoverDelay
    ) {
        List<SynchronizerFactoryWithState> synchronizerFactories = synchronizers
            .stream()
            .map(SynchronizerFactoryWithState::new)
            // Collect to an ArrayList to ensure mutability.
            .collect(Collectors.toCollection(ArrayList::new));

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
        // getEarliestUnparkTime synchronizes on the lock of the source manager, so it is safe to call from the thread
        // that builds the conditions.
        // TODO: Decide whether to keep this guarantee, or to require that conditions are built only on the data
        // source thread.
        this.conditionFactories.add(new UnparkCondition.Factory(sharedExecutor, sourceManager::getEarliestUnparkTime));
        this.parkInitialDelay = parkInitialDelay;
        this.parkMaxDelay = parkMaxDelay;
        this.failoverDelay = failoverDelay;
    }

    private void run() {
        Thread runThread = new Thread(() -> {
            if (!sourceManager.hasAvailableSources()) {
                // There are not any initializer or synchronizers, so we are at the best state that
                // can be achieved.
                logger.warn(
                    "LaunchDarkly client will not connect to LaunchDarkly for feature flag data due to no initializers or synchronizers configured."
                );
                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                startFuture.complete(true);
                return;
            }

            InitializerOutcome initializerOutcome = InitializerOutcome.completed();
            if (sourceManager.hasInitializers()) {
                initializerOutcome = runInitializers();
            }

            // If an initializer signalled FDv1 fallback, block every FDv2 synchronizer in
            // one shot via fdv1Fallback() (which also unblocks the FDv1 fallback
            // synchronizer, if one is configured). If FDv1 is configured we hand off to it;
            // otherwise we halt the data system. This takes precedence over the standard
            // synchronizer chain -- the FDv2 synchronizers are not given a chance to run.
            if (initializerOutcome.fallbackToFDv1) {
                sourceManager.fdv1Fallback();
                if (sourceManager.hasFDv1Fallback()) {
                    logger.warn("Initializer requested fallback to FDv1; switching to FDv1 fallback synchronizer.");
                } else {
                    logger.warn("Initializer requested fallback to FDv1, but no FDv1 fallback synchronizer is configured.");
                    dataSourceUpdates.updateStatus(
                        DataSourceStatusProvider.State.OFF,
                        initializerOutcome.errorInfo);
                    startFuture.complete(false);
                    return;
                }
            }

            if(!sourceManager.hasAvailableSynchronizers()) {
                // If already completed by the initializers, then this will have no effect.
                if (!isInitialized()) {
                    // If we have no synchronizers, and we didn't manage to initialize, and we aren't shutting down,
                    // then that was unexpected, and we will report it.
                    maybeReportUnexpectedExhaustion("All initializers exhausted and there are no available synchronizers.");
                }
                // If already completed has no effect.
                startFuture.complete(false);
                return;
            }

            boolean haltedByDirective = runSynchronizers();

            if (!haltedByDirective) {
                // If we had synchronizers, and we ran out of them, and we aren't shutting down, then that was unexpected,
                // and we will report it.
                maybeReportUnexpectedExhaustion("All data source acquisition methods have been exhausted.");
            }

            // If we had initialized at some point, then the future will already be complete and this will be ignored.
            startFuture.complete(false);
        });
        runThread.setName("LaunchDarkly-SDK-Server-FDv2DataSource");
        runThread.setDaemon(true);
        runThread.setPriority(threadPriority);
        runThread.start();
    }

    /**
     * Runs the configured initializers in order until one succeeds, the list is exhausted,
     * or one signals an FDv1 fallback directive. Returns an {@link InitializerOutcome}
     * describing whether the caller should switch to the FDv1 fallback synchronizer.
     * <p>
     * If an initializer's result carries {@link FDv2SourceResult#isFdv1Fallback()}, any
     * accompanying payload is applied first so evaluations can serve the server-provided
     * data while the FDv1 synchronizer is brought up. When the directive accompanies an
     * error result the underlying error is preserved on the returned outcome so the
     * caller can surface it on a subsequent OFF status (when no fallback is configured).
     */
    private InitializerOutcome runInitializers() {
        boolean anyDataReceived = false;
        Initializer initializer = sourceManager.getNextInitializerAndSetActive();
        while (initializer != null) {
            String initializerName = initializer.name();
            logger.info("Initializer '{}' is starting.", initializerName);
            try {
                try (FDv2SourceResult result = initializer.run().get()) {
                    DataSourceStatusProvider.ErrorInfo fallbackErrorInfo = null;
                    switch (result.getResultType()) {
                        case CHANGE_SET:
                            dataSourceUpdates.apply(result.getChangeSet());
                            anyDataReceived = true;
                            logger.info("Initialized via '{}'.", initializerName);
                            if (!result.getChangeSet().getSelector().isEmpty()) {
                                // A defined selector marks initialization complete -- match Go/Python/Ruby
                                // behavior. A selectorless basis is applied so evaluations can serve it,
                                // and once the initializer chain is fully exhausted that applied data is
                                // also enough to consider initialization complete (see the post-loop
                                // block below); but mid-chain we don't yet flip to VALID, so a later
                                // initializer can still produce a selectorful basis if one is available.
                                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                                startFuture.complete(true);
                                if (result.isFdv1Fallback()) {
                                    return InitializerOutcome.fallbackToFDv1(null);
                                }
                                return InitializerOutcome.completed();
                            }
                            break;
                        case STATUS:
                            FDv2SourceResult.Status status = result.getStatus();
                            switch (status.getState()) {
                                case INTERRUPTED:
                                case TERMINAL_ERROR:
                                    logger.warn("Initializer '{}' failed: {}",
                                        initializerName,
                                        detailForError(status.getErrorInfo()));
                                    fallbackErrorInfo = status.getErrorInfo();
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
                    // FDv1 fallback may ride along on either a successful CHANGE_SET (with no
                    // selector, so initialization is incomplete) or on a STATUS error result.
                    // In either case, the SDK must halt the FDv2 chain immediately and switch
                    // to the FDv1 fallback synchronizer; the eventual VALID status will come
                    // from the FDv1 synchronizer once it serves a selectorful payload.
                    if (result.isFdv1Fallback()) {
                        return InitializerOutcome.fallbackToFDv1(fallbackErrorInfo);
                    }
                }
            } catch (ExecutionException | InterruptedException | CancellationException e) {
                // The data source updates handler will filter the state during initializing, but this
                // will make the error information available.
                dataSourceUpdates.updateStatus(
                    DataSourceStatusProvider.State.INTERRUPTED,
                    new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        new Date().toInstant()));
                Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                logger.error("Error running initializer '{}': {}",
                    initializerName,
                    root.getMessage() != null ? root.getMessage() : LogValues.exceptionSummary(root));
            }
            initializer = sourceManager.getNextInitializerAndSetActive();
        }
        // No initializer produced a selectorful basis, but at least one initializer applied
        // a selectorless basis. Treat that as enough to consider the data system initialized
        // now that the entire initializer chain is exhausted -- evaluations can serve the
        // applied data, and the synchronizer phase (when configured) will continue from
        // there. Without this, an SDK configured with only selectorless initializers and no
        // synchronizer would never transition out of INITIALIZING.
        if (anyDataReceived) {
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
            startFuture.complete(true);
        }
        return InitializerOutcome.completed();
    }

    /**
     * Outcome of {@link #runInitializers()} relaying whether the SDK should perform a
     * server-directed FDv1 fallback before the synchronizer phase begins.
     */
    private static final class InitializerOutcome {
        final boolean fallbackToFDv1;
        final DataSourceStatusProvider.ErrorInfo errorInfo;

        private InitializerOutcome(boolean fallbackToFDv1, DataSourceStatusProvider.ErrorInfo errorInfo) {
            this.fallbackToFDv1 = fallbackToFDv1;
            this.errorInfo = errorInfo;
        }

        static InitializerOutcome completed() {
            return new InitializerOutcome(false, null);
        }

        static InitializerOutcome fallbackToFDv1(DataSourceStatusProvider.ErrorInfo errorInfo) {
            return new InitializerOutcome(true, errorInfo);
        }
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
                .map(ConditionFactory::build).filter(Objects::nonNull).collect(Collectors.toList());
        }
        // The synchronizer can both fall back and recover.
        return conditionFactories.stream()
            .map(ConditionFactory::build).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Runs the configured synchronizers, falling back / recovering as conditions allow,
     * until the list is exhausted, the data source is closed, or a server-directed FDv1
     * fallback halts the data system.
     *
     * @return true when {@code runSynchronizers} halted in response to a server-directed
     *         FDv1 fallback directive that could not be satisfied (no FDv1 fallback
     *         synchronizer configured) -- the caller should NOT report exhaustion in
     *         that case because OFF has already been published with a specific error.
     */
    private boolean runSynchronizers() {
        // When runSynchronizers exists, no matter how it exits, the synchronizerStateManager will be closed.
        try {
            Synchronizer synchronizer = buildNextAvailableSynchronizer();

            // We want to continue running synchronizers for as long as any are available.
            while (synchronizer != null) {
                String synchronizerName = synchronizer.name();
                logger.info("Synchronizer '{}' is starting.", synchronizerName);
                resetSynchronizerStatusDedupe();
                boolean parked = false;
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
                                        logger.info(
                                            "Fallback condition met, falling back from synchronizer '{}'.",
                                            synchronizer.name()
                                        );
                                        break;
                                    case RECOVERY:
                                        // For recovery, we will start at the first available synchronizer.
                                        // So we reset the source index, and finding the source will start at the beginning.
                                        sourceManager.resetSourceIndex();
                                        logger.info(
                                            "Recovery condition met, moving from synchronizer '{}' to primary synchronizer.",
                                            synchronizer.name()
                                        );
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

                            try (FDv2SourceResult result = (FDv2SourceResult) res) {
                                conditions.inform(result);

                                if (result.isResetThresholdMet()) {
                                    sourceManager.resetCurrentSynchronizerParkCount();
                                }

                                switch (result.getResultType()) {
                                    case CHANGE_SET:
                                        // A data update breaks the "in a row" streak for status deduplication.
                                        resetSynchronizerStatusDedupe();
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
                                                maybeLogSynchronizerStatusChange(
                                                    synchronizer.name(),
                                                    status.getState()
                                                );
                                                // Handled by conditions.
                                                dataSourceUpdates.updateStatus(
                                                    DataSourceStatusProvider.State.INTERRUPTED,
                                                    status.getErrorInfo());
                                                break;
                                            case SHUTDOWN:
                                                maybeLogSynchronizerStatusChange(
                                                    synchronizer.name(),
                                                    status.getState()
                                                );
                                                // We should be overall shutting down.
                                                logger.debug("Synchronizer shutdown.");
                                                return false;
                                            case TERMINAL_ERROR:
                                                maybeLogSynchronizerStatusChange(
                                                    synchronizer.name(),
                                                    status.getState()
                                                );
                                                parkActiveSynchronizer(synchronizer.name(), status.getErrorInfo());
                                                parked = true;
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
                                // We have been requested to fall back to FDv1. Block every FDv2
                                // synchronizer in one shot via fdv1Fallback() (which also unblocks the
                                // FDv1 fallback synchronizer, if one is configured). If FDv1 is
                                // configured we hand off to it; otherwise we halt the data system.
                                // An FDv1 fallback synchronizer asking to fall back again is ignored
                                // -- shouldn't happen in practice.
                                if (result.isFdv1Fallback()
                                    && !sourceManager.isCurrentSynchronizerFDv1Fallback()) {
                                    sourceManager.fdv1Fallback();
                                    if (sourceManager.hasFDv1Fallback()) {
                                        logger.info("Falling back to an FDv1 fallback synchronizer.");
                                        running = false;
                                    } else {
                                        // When the directive is signalled but no FDv1 fallback synchronizer
                                        // is configured, halt the data system entirely. Surface OFF with
                                        // the most recent error info (if any) and exit the synchronizer
                                        // loop terminally.
                                        logger.warn(
                                            "Synchronizer '{}' requested FDv1 fallback, but no FDv1 fallback synchronizer is configured; halting the data system.",
                                            synchronizer.name()
                                        );
                                        DataSourceStatusProvider.ErrorInfo offError =
                                            result.getStatus() != null ? result.getStatus().getErrorInfo() : null;
                                        dataSourceUpdates.updateStatus(
                                            DataSourceStatusProvider.State.OFF,
                                            offError);
                                        startFuture.complete(false);
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                } catch (ExecutionException | InterruptedException | RuntimeException e) {
                    DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        new Date().toInstant()
                    );
                    dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED, errorInfo);
                    Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    logger.error("Error running synchronizer '{}': {}",
                        synchronizer.name(),
                        root.getMessage() != null ? root.getMessage() : LogValues.exceptionSummary(root));
                    parkActiveSynchronizer(synchronizer.name(), errorInfo);
                    parked = true;
                }
                if (parked && !waitUnlessClosed(jittered(failoverDelay))) {
                    break;
                }
                // Get the next available synchronizer and set it active
                synchronizer = buildNextAvailableSynchronizer();
            }
        } catch (Exception e) {
            // We are not expecting to encounter this situation, but if we do, then we should log it.
            logger.error("Unexpected error in data source: {}", e.toString());
        } finally {
            sourceManager.close();
        }
        return false;
    }

    private static String detailForError(DataSourceStatusProvider.ErrorInfo errorInfo) {
        if (errorInfo == null) {
            return "unknown error";
        }
        if (errorInfo.getMessage() != null && !errorInfo.getMessage().isEmpty()) {
            return errorInfo.getMessage();
        }
        return errorInfo.toString();
    }

    /**
     * Waits for the given duration, or until the data source is closed.
     *
     * @param duration the time to wait
     * @return true if the full duration passed, or false if the data source was closed
     */
    private boolean waitUnlessClosed(Duration duration) {
        try {
            closedSignal.get(Math.max(0, duration.toMillis()), TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /**
     * Subtracts a random jitter of up to half of the given delay.
     *
     * @param delay the delay before jitter
     * @return the delay after jitter
     */
    private static Duration jittered(Duration delay) {
        return delay.minusMillis(ThreadLocalRandom.current().nextLong(delay.toMillis() / 2 + 1));
    }

    /**
     * Computes the park delay for a synchronizer.
     *
     * @param parkCount the park count of the synchronizer, including the current failure
     * @return the park delay
     */
    private Duration parkDelay(int parkCount) {
        double delayMillis = Math.min(
            parkInitialDelay.toMillis() * Math.pow(2, parkCount - 1),
            parkMaxDelay.toMillis());
        return jittered(Duration.ofMillis((long) delayMillis));
    }

    /**
     * Parks the active synchronizer after an unexpected failure.
     *
     * @param synchronizerName the name of the active synchronizer, or null if it failed to build
     * @param errorInfo the error that caused the park
     */
    private void parkActiveSynchronizer(String synchronizerName, DataSourceStatusProvider.ErrorInfo errorInfo) {
        Duration parkDelay = parkDelay(sourceManager.incrementCurrentSynchronizerParkCount());
        sourceManager.parkCurrentSynchronizer(Instant.now().plus(parkDelay));
        logger.warn(
            "{} is backing off for {}s after an unexpected error ({}).",
            synchronizerName != null ? "Synchronizer '" + synchronizerName + "'" : "A synchronizer that failed to build",
            parkDelay.getSeconds(),
            detailForError(errorInfo)
        );
    }

    /**
     * Gets the next available synchronizer, builds it, and sets it active. While no synchronizer is available, this
     * waits for the earliest unpark time. A synchronizer that fails to build is parked, and the next available
     * synchronizer is tried after the failover delay.
     *
     * @return the active synchronizer, or null if the data source was closed
     */
    private Synchronizer buildNextAvailableSynchronizer() {
        while (!closed) {
            Synchronizer synchronizer;
            try {
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            } catch (RuntimeException e) {
                DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                    DataSourceStatusProvider.ErrorKind.UNKNOWN,
                    0,
                    e.toString(),
                    Instant.now()
                );
                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED, errorInfo);
                parkActiveSynchronizer(null, errorInfo);
                if (!waitUnlessClosed(jittered(failoverDelay))) {
                    return null;
                }
                continue;
            }
            if (synchronizer != null) {
                return synchronizer;
            }

            Instant earliestUnparkTime = sourceManager.getEarliestUnparkTime();
            Duration wait;
            if (earliestUnparkTime == null) {
                // This should never happen.
                wait = parkInitialDelay;
                logger.error("No synchronizer can be started; checking again in {}s.", wait.getSeconds());
            } else {
                wait = Duration.between(Instant.now(), earliestUnparkTime);
                logger.warn("All synchronizers are backing off for at least {}s.", Math.max(0, wait.getSeconds()));
            }
            if (!waitUnlessClosed(wait)) {
                return null;
            }
            // Start the most-preferred synchronizer that is no longer parked.
            sourceManager.resetSourceIndex();
        }
        return null;
    }

    private void resetSynchronizerStatusDedupe() {
        lastLoggedSynchronizerDedupeName = null;
        lastLoggedSynchronizerDedupeStatus = null;
    }

    private void maybeLogSynchronizerStatusChange(String sourceName, SourceSignal state) {
        if (state == SourceSignal.GOODBYE) {
            return;
        }
        if (sourceName != null
            && sourceName.equals(lastLoggedSynchronizerDedupeName)
            && state == lastLoggedSynchronizerDedupeStatus) {
            return;
        }
        lastLoggedSynchronizerDedupeName = sourceName;
        lastLoggedSynchronizerDedupeStatus = state;
        logger.info("Synchronizer '{}' reported status: {}.", sourceName, state.name());
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
        closedSignal.complete(null);
        // If there is an active source, we will shut it down, and that will result in the loop handling that source
        // exiting.
        // If we do not have an active source, then the loop will check isShutdown when attempting to set one. When
        // it detects shutdown, it will exit the loop.
        sourceManager.close();

        dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.OFF, null);

        // If this is already set, then this has no impact.
        startFuture.complete(false);
    }

    private void maybeReportUnexpectedExhaustion(String message) {
        if(!closed) {
            dataSourceUpdates.updateStatus(
                DataSourceStatusProvider.State.OFF,
                // If the data source was closed, then we just report we are OFF without an
                // associated error.
                new DataSourceStatusProvider.ErrorInfo(
                    DataSourceStatusProvider.ErrorKind.UNKNOWN,
                    0,
                    message,
                    new Date().toInstant())
            );
        }
    }

    /**
     * Helper class to manage the lifecycle of conditions with automatic cleanup.
     *
     * <p>Before the aggregate completes, {@link #getFuture()} returns a
     * <em>fresh</em> {@link CompletableFuture} per call. This matters because
     * the run loop calls {@code CompletableFuture.anyOf(getFuture(),
     * synchronizerNext)} on every iteration: if {@code getFuture()} returned
     * the shared underlying aggregate while it was still pending, each
     * {@code anyOf} call would permanently attach an {@code OrRelay}
     * {@code Completion} to its {@code stack}. On a healthy primary
     * synchronizer that streams ChangeSets without ever arming the fallback
     * timer, the aggregate never completes, so those Completion nodes would
     * accumulate monotonically for the synchronizer's full tenure -- a real
     * memory leak proportional to event rate.
     *
     * <p>After the aggregate completes, {@link #getFuture()} returns the
     * aggregate directly: any continuation registered on an already-completed
     * CompletableFuture fires synchronously at registration time and is
     * removed from the stack immediately by {@code cleanStack}, so the same
     * accumulation cannot happen.
     *
     * <p>Fresh pre-completion futures are tracked in a {@link WeakHashMap}-backed
     * set, so a fresh future whose only strong references were in the caller's
     * loop iteration becomes garbage-collectable -- and automatically removed
     * from {@code pending} -- once that iteration ends.
     *
     * <p>Package-private (rather than private) so that direct unit tests can
     * exercise the API surface and assert per-call distinctness.
     */
    static class Conditions implements AutoCloseable {
        private final Object lock = new Object();

        /**
         * The conditions, with at most one of each type. Accessed only under {@code lock}.
         */
        private final Map<Condition.ConditionType, Condition> conditions =
            new EnumMap<>(Condition.ConditionType.class);

        /**
         * The types of the conditions that the aggregate has completed with. Accessed only under {@code lock}.
         */
        private final Set<Condition.ConditionType> reported = EnumSet.noneOf(Condition.ConditionType.class);

        /**
         * A future over the futures of all the conditions. It completes with the first condition that is met.
         * Accessed only under {@code lock}.
         */
        private CompletableFuture<Object> aggregate;

        /**
         * Tracks futures previously returned by {@link #getFuture()} that have
         * not yet been completed. Held weakly via {@link WeakHashMap} so that
         * fresh futures abandoned by the caller (the typical end-of-iteration
         * case) become GC-collectable. Set to {@code null} once the aggregate
         * has fired and the entries have been drained. Accessed only under
         * {@code lock}.
         */
        private Set<CompletableFuture<Object>> pending;

        public Conditions(List<Condition> conditions) {
            for (Condition condition : conditions) {
                this.conditions.put(condition.getType(), condition);
            }
            arm();
        }

        /**
         * Builds the aggregate future over the conditions.
         */
        private void arm() {
            synchronized (lock) {
                CompletableFuture<?>[] unreported = conditions.values().stream()
                    .filter(c -> !reported.contains(c.getType()))
                    .map(Condition::execute)
                    .toArray(CompletableFuture[]::new);
                CompletableFuture<Object> newAggregate = unreported.length == 0
                    ? new CompletableFuture<>() // Never completes if no conditions are unreported
                    : CompletableFuture.anyOf(unreported);
                aggregate = newAggregate;
                // Start a new set only if the previous aggregate drained it. Otherwise, the futures in it are still
                // waiting, and the new aggregate completes them.
                if (pending == null) {
                    pending = Collections.newSetFromMap(new WeakHashMap<>());
                }

                // Single permanent listener. This is the only Completion node ever
                // attached to aggregate.stack while the aggregate is still pending
                // -- subsequent pre-completion getFuture() calls do not touch the
                // aggregate at all.
                newAggregate.whenComplete((result, throwable) -> {
                    List<CompletableFuture<Object>> snapshot;
                    synchronized (lock) {
                        // A newer aggregate replaced this one, and it completes the pending futures instead.
                        if (aggregate != newAggregate || pending == null) {
                            return;
                        }
                        if (throwable == null) {
                            reported.add(((Condition) result).getType());
                        }
                        // Copy under the lock: the ArrayList holds strong
                        // references so entries that survived GC to this point
                        // stay alive until we complete them below.
                        snapshot = new ArrayList<>(pending);
                        pending = null;
                    }
                    for (CompletableFuture<Object> cf : snapshot) {
                        if (throwable != null) {
                            cf.completeExceptionally(throwable);
                        } else {
                            cf.complete(result);
                        }
                    }
                });
            }
        }

        /**
         * Returns a future that completes with a met condition that this method has not reported before.
         */
        public CompletableFuture<Object> getFuture() {
            synchronized (lock) {
                if (pending == null) {
                    // The aggregate has fired.
                    if (aggregate.isCompletedExceptionally()) {
                        return aggregate;
                    }
                    // Wait for the conditions that have not been reported.
                    arm();
                    if (pending == null) {
                        // Another condition was already met, so the new aggregate has fired too.
                        return aggregate;
                    }
                }
                CompletableFuture<Object> fresh = new CompletableFuture<>();
                pending.add(fresh);
                return fresh;
            }
        }

        /**
         * True if the condition of the given type has been reported as met.
         *
         * @param type the type of condition
         * @return true if the condition is met
         */
        public boolean isMet(Condition.ConditionType type) {
            synchronized (lock) {
                return reported.contains(type);
            }
        }

        /**
         * Closes the condition of the given type, if there is one, and puts the replacement in its place.
         *
         * @param type the type of condition
         * @param replacement the new condition, or null to leave no condition of that type
         */
        public void replace(Condition.ConditionType type, Condition replacement) {
            synchronized (lock) {
                Condition old = conditions.remove(type);
                if (old != null) {
                    old.close();
                }
                reported.remove(type);
                if (replacement != null) {
                    conditions.put(type, replacement);
                }
                arm();
            }
        }

        public void inform(FDv2SourceResult result) {
            synchronized (lock) {
                conditions.values().forEach(c -> c.inform(result));
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                conditions.values().forEach(Condition::close);
                if (pending != null) {
                    pending.clear();
                }
            }
        }
    }
}
