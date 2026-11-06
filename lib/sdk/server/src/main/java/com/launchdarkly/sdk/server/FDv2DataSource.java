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
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class FDv2DataSource implements DataSource {
    /**
     * Default fallback timeout of 2 minutes.
     */
    private static final Duration DEFAULT_FALLBACK_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Default recovery timeout of 5 minutes.
     */
    private static final Duration DEFAULT_RECOVERY_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Default base of the park delay that follows an unexpected failure.
     */
    private static final Duration DEFAULT_PARK_INITIAL_DELAY = Duration.ofMinutes(5);

    /**
     * Default maximum of the park delay.
     */
    private static final Duration DEFAULT_PARK_MAX_DELAY = Duration.ofHours(1);

    /**
     * Default base of the failover delay that follows a park.
     */
    private static final Duration DEFAULT_FAILOVER_DELAY = Duration.ofSeconds(1);

    /**
     * Default duration of a fallback to FDv1, when the directive does not give one.
     */
    private static final Duration DEFAULT_FDV1_FALLBACK_TTL = Duration.ofHours(1);

    private final SourceManager sourceManager;

    private final Duration fallbackTimeout;
    private final Duration recoveryTimeout;
    private final Duration parkInitialDelay;
    private final Duration parkMaxDelay;
    private final Duration failoverDelay;
    private final Duration defaultFDv1FallbackTTL;

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
            LDLogger logger) {
        this(initializers,
                synchronizers,
                fdv1DataSourceFactory,
                dataSourceUpdates,
                threadPriority,
                logger,
                DEFAULT_FALLBACK_TIMEOUT,
                DEFAULT_RECOVERY_TIMEOUT,
                DEFAULT_PARK_INITIAL_DELAY,
                DEFAULT_PARK_MAX_DELAY,
                DEFAULT_FAILOVER_DELAY,
                DEFAULT_FDV1_FALLBACK_TTL);
    }

    /**
     * Creates a data source with the given timeouts and delays. Tests use this constructor to shorten them.
     */
    public FDv2DataSource(
            ImmutableList<DataSourceFactory<Initializer>> initializers,
            ImmutableList<DataSourceFactory<Synchronizer>> synchronizers,
            DataSourceFactory<Synchronizer> fdv1DataSourceFactory,
            DataSourceUpdateSinkV2 dataSourceUpdates,
            int threadPriority,
            LDLogger logger,
            Duration fallbackTimeout,
            Duration recoveryTimeout,
            Duration parkInitialDelay,
            Duration parkMaxDelay,
            Duration failoverDelay,
            Duration defaultFDv1FallbackTTL) {
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
        this.fallbackTimeout = fallbackTimeout;
        this.recoveryTimeout = recoveryTimeout;
        this.parkInitialDelay = parkInitialDelay;
        this.parkMaxDelay = parkMaxDelay;
        this.failoverDelay = failoverDelay;
        this.defaultFDv1FallbackTTL = defaultFDv1FallbackTTL;
    }

    private void run() {
        Thread runThread = new Thread(() -> {
            if (!sourceManager.hasAvailableSources()) {
                // There are not any initializer or synchronizers, so we are at the best state that
                // can be achieved.
                logger.warn(
                        "LaunchDarkly client will not connect to LaunchDarkly for feature flag data due to no initializers or synchronizers configured.");
                dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.VALID, null);
                startFuture.complete(true);
                return;
            }

            InitializerOutcome initializerOutcome = InitializerOutcome.completed();
            if (sourceManager.hasInitializers()) {
                initializerOutcome = runInitializers();
            }

            // If an initializer signalled FDv1 fallback, fall back to FDv1 before any synchronizer runs.
            if (initializerOutcome.fallbackToFDv1) {
                fallBackToFDv1(jittered(defaultFDv1FallbackTTL), initializerOutcome.errorInfo);
            }

            if (!sourceManager.hasFDv2Synchronizers() && !sourceManager.hasAvailableSynchronizers()) {
                // If already completed by the initializers, then this will have no effect.
                if (!isInitialized()) {
                    // If we have no synchronizers, and we didn't manage to initialize, and we aren't shutting down,
                    // then that was unexpected, and we will report it.
                    maybeReportUnexpectedExhaustion(
                            "All initializers exhausted and there are no available synchronizers.");
                }
                // If already completed has no effect.
                startFuture.complete(false);
                return;
            }

            runSynchronizers();

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
     * Runs the configured synchronizers, falling back / recovering as conditions allow,
     * until the data source is closed or a synchronizer reports that it shut down.
     */
    private void runSynchronizers() {
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
                    // Fallback and recovery apply only when another synchronizer can take over.
                    boolean canFallBack = sourceManager.getAvailableSynchronizerCount() > 1;
                    boolean canRecover = canFallBack && !sourceManager.isPrimeSynchronizer();
                    MonotonicTime recoveryTime = canRecover ? MonotonicTime.now().plus(recoveryTimeout) : null;
                    MonotonicTime fallbackTime = null;
                    // True once the loop has seen the deadline pass.
                    boolean recoveryMet = false;
                    boolean fallbackMet = false;
                    // The result the loop is waiting for, or null if it has not asked the synchronizer for one.
                    CompletableFuture<FDv2SourceResult> nextResultFuture = null;
                    while (running) {
                        if (nextResultFuture == null) {
                            nextResultFuture = synchronizer.next();
                        }

                        // Wait for the next result, or for the next deadline that the loop has not seen pass.
                        MonotonicTime wakeTime = earliest(
                                recoveryMet ? null : recoveryTime,
                                fallbackMet ? null : fallbackTime,
                                sourceManager.getEarliestUnparkTime(),
                                sourceManager.getFDv1FallbackEndTime());
                        if (awaitUntil(nextResultFuture, wakeTime)) {
                            try (FDv2SourceResult result = nextResultFuture.get()) {
                                // The pending result has arrived.
                                nextResultFuture = null;

                                if (result.isResetThresholdMet()) {
                                    sourceManager.resetCurrentSynchronizerParkCount();
                                }

                                switch (result.getResultType()) {
                                    case CHANGE_SET:
                                        // A data update breaks the "in a row" streak for status deduplication.
                                        resetSynchronizerStatusDedupe();
                                        // The synchronizer is healthy, so it no longer needs to fall back.
                                        fallbackTime = null;
                                        fallbackMet = false;
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
                                                        status.getState());
                                                // The fallback timeout runs from the first interruption.
                                                if (canFallBack && fallbackTime == null) {
                                                    fallbackTime = MonotonicTime.now().plus(fallbackTimeout);
                                                }
                                                dataSourceUpdates.updateStatus(
                                                        DataSourceStatusProvider.State.INTERRUPTED,
                                                        status.getErrorInfo());
                                                break;
                                            case SHUTDOWN:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState());
                                                if (closed) {
                                                    // Closing the data source shut down the synchronizer.
                                                    logger.debug("Synchronizer shutdown.");
                                                    return;
                                                }
                                                // The synchronizer shut down by itself, so park it.
                                                parkActiveSynchronizer(synchronizer.name(), status.getErrorInfo());
                                                parked = true;
                                                running = false;
                                                dataSourceUpdates.updateStatus(
                                                        DataSourceStatusProvider.State.INTERRUPTED,
                                                        status.getErrorInfo());
                                                break;
                                            case TERMINAL_ERROR:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState());
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
                                if (result.isFdv1Fallback()) {
                                    if (sourceManager.isCurrentSynchronizerFDv1Fallback()) {
                                        // A directive during the FDv1 fallback replaces its TTL, and FDv1 keeps running.
                                        Duration fallbackTTL = jittered(defaultFDv1FallbackTTL);
                                        sourceManager.startFDv1Fallback(MonotonicTime.now().plus(fallbackTTL));
                                        logger.info("FDv2 retry scheduled in {}s.", fallbackTTL.getSeconds());
                                    } else {
                                        // A directive to fall back to FDv1 ends the run of an FDv2 synchronizer.
                                        fallBackToFDv1(
                                                jittered(defaultFDv1FallbackTTL),
                                                result.getStatus() != null ? result.getStatus().getErrorInfo() : null);
                                        // The directive takes over, so there is no failover delay after a park.
                                        parked = false;
                                        running = false;
                                    }
                                }
                            }
                        }
                        if (!running) {
                            break;
                        }

                        // Check every deadline against the current time.
                        MonotonicTime now = MonotonicTime.now();
                        sourceManager.unparkExpiredSynchronizers();
                        if (sourceManager.endFDv1FallbackIfExpired()) {
                            logger.info("Fallback TTL elapsed, restarting FDv2 data sources.");
                            break;
                        }
                        boolean recoveryJustMet = !recoveryMet && recoveryTime != null && !now.isBefore(recoveryTime);
                        boolean fallbackJustMet = !fallbackMet && fallbackTime != null && !now.isBefore(fallbackTime);
                        recoveryMet = recoveryMet || recoveryJustMet;
                        fallbackMet = fallbackMet || fallbackJustMet;

                        // Recovery takes precedence over fallback.
                        if (recoveryMet && sourceManager.hasMorePreferredAvailableSynchronizer()) {
                            // For recovery, we will start at the first available synchronizer.
                            // So we reset the source index, and finding the source will start at the beginning.
                            sourceManager.resetSourceIndex();
                            logger.info(
                                    "Recovery condition met, moving from synchronizer '{}' to primary synchronizer.",
                                    synchronizer.name());
                            break;
                        }
                        if (fallbackMet && sourceManager.hasOtherAvailableSynchronizer()) {
                            logger.info(
                                    "Fallback condition met, falling back from synchronizer '{}'.",
                                    synchronizer.name());
                            break;
                        }

                        // Keep running this synchronizer.
                        if (fallbackJustMet) {
                            logger.info(
                                    "Fallback condition met, but no other synchronizer is available; continuing with synchronizer '{}'.",
                                    synchronizer.name());
                        }
                        if (recoveryJustMet) {
                            logger.info(
                                    "Recovery condition met, but no more-preferred synchronizer is available; continuing with synchronizer '{}'.",
                                    synchronizer.name());
                        }
                    }
                } catch (ExecutionException | InterruptedException | RuntimeException e) {
                    DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.UNKNOWN,
                            0,
                            e.toString(),
                            new Date().toInstant());
                    dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED, errorInfo);
                    Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    logger.error("Error running synchronizer '{}': {}",
                            synchronizer.name(),
                            root.getMessage() != null ? root.getMessage() : LogValues.exceptionSummary(root));
                    parkActiveSynchronizer(synchronizer.name(), errorInfo);
                    parked = true;
                }
                // The run of this synchronizer has ended, so stop it.
                sourceManager.closeActiveSource();

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
     * Waits for the future to complete, or until the given time.
     *
     * @param future the future to wait for
     * @param time the time to stop waiting, or null to wait until the future completes
     * @return true if the future completed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private static boolean awaitUntil(CompletableFuture<?> future, MonotonicTime time) throws InterruptedException {
        try {
            if (time == null) {
                future.get();
            } else {
                future.get(Math.max(0, MonotonicTime.now().until(time).toNanos()), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            // The future completed with an error, which the caller gets from the future.
            return true;
        }
    }

    /**
     * Gets the earliest of the given times.
     *
     * @param times the times, any of which may be null
     * @return the earliest time, or null if every time is null
     */
    private static MonotonicTime earliest(MonotonicTime... times) {
        MonotonicTime earliest = null;
        for (MonotonicTime time : times) {
            if (time != null && (earliest == null || time.isBefore(earliest))) {
                earliest = time;
            }
        }
        return earliest;
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
        sourceManager.parkCurrentSynchronizer(MonotonicTime.now().plus(parkDelay));
        logger.warn(
                "{} is backing off for {}s after an unexpected error ({}).",
                synchronizerName != null ? "Synchronizer '" + synchronizerName + "'"
                        : "A synchronizer that failed to build",
                parkDelay.getSeconds(),
                detailForError(errorInfo));
    }

    /**
     * Falls back to FDv1 for the given duration. If no FDv1 fallback synchronizer is configured, no synchronizer runs
     * until the fallback ends.
     *
     * @param fallbackTTL how long the fallback lasts
     * @param errorInfo the error that came with the directive, or null
     */
    private void fallBackToFDv1(Duration fallbackTTL, DataSourceStatusProvider.ErrorInfo errorInfo) {
        sourceManager.startFDv1Fallback(MonotonicTime.now().plus(fallbackTTL));
        if (sourceManager.hasFDv1Fallback()) {
            logger.info("Falling back to FDv1 fallback synchronizer.");
        } else {
            logger.warn("Fallback to FDv1 was requested, but no FDv1 fallback synchronizer is configured.");
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED, errorInfo);
        }
        logger.info("FDv2 retry scheduled in {}s.", fallbackTTL.getSeconds());
    }

    /**
     * Gets the next available synchronizer, builds it, and sets it active. If the FDv1 fallback has expired, this
     * first makes the FDv2 synchronizers available again. While no synchronizer is available, this waits for the
     * earliest unpark time or the end of the FDv1 fallback, whichever comes first. A synchronizer that fails to build
     * is parked, and the next available synchronizer is tried after the failover delay.
     *
     * @return the active synchronizer, or null if the data source was closed
     */
    private Synchronizer buildNextAvailableSynchronizer() {
        while (!closed) {
            if (sourceManager.endFDv1FallbackIfExpired()) {
                logger.info("Fallback TTL elapsed, restarting FDv2 data sources.");
            }
            Synchronizer synchronizer;
            try {
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            } catch (RuntimeException e) {
                DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        Instant.now());
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

            MonotonicTime earliestUnparkTime = sourceManager.getEarliestUnparkTime();
            MonotonicTime fallbackEndTime = sourceManager.getFDv1FallbackEndTime();
            Duration wait;
            if (earliestUnparkTime == null && fallbackEndTime == null) {
                // This should never happen.
                wait = parkInitialDelay;
                logger.error("No synchronizer can be started; checking again in {}s.", wait.getSeconds());
            } else if (earliestUnparkTime == null) {
                wait = MonotonicTime.now().until(fallbackEndTime);
                logger.warn("No synchronizer is available until the FDv1 fallback ends in {}s.",
                        Math.max(0, wait.getSeconds()));
            } else if (fallbackEndTime == null) {
                wait = MonotonicTime.now().until(earliestUnparkTime);
                logger.warn("All synchronizers are backing off for at least {}s.", Math.max(0, wait.getSeconds()));
            } else {
                MonotonicTime earliest =
                        fallbackEndTime.isBefore(earliestUnparkTime) ? fallbackEndTime : earliestUnparkTime;
                wait = MonotonicTime.now().until(earliest);
                logger.warn("All synchronizers are backing off for at least {}s.", Math.max(0, wait.getSeconds()));
            }
            // No synchronizer is running until the wait ends.
            dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED, null);
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
        if (!closed) {
            dataSourceUpdates.updateStatus(
                    DataSourceStatusProvider.State.OFF,
                    // If the data source was closed, then we just report we are OFF without an
                    // associated error.
                    new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.UNKNOWN,
                            0,
                            message,
                            new Date().toInstant()));
        }
    }
}
