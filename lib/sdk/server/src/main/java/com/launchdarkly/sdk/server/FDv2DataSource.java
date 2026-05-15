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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
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
                .map(ConditionFactory::build).collect(Collectors.toList());
        }
        // The synchronizer can both fall back and recover.
        return conditionFactories.stream().map(ConditionFactory::build).collect(Collectors.toList());
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
            Synchronizer synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();

            // We want to continue running synchronizers for as long as any are available.
            while (synchronizer != null) {
                String synchronizerName = synchronizer.name();
                logger.info("Synchronizer '{}' is starting.", synchronizerName);
                resetSynchronizerStatusDedupe();
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
                                                sourceManager.blockCurrentSynchronizer();
                                                logger.warn(
                                                    "Synchronizer '{}' permanently failed and will not be used again until application restart.",
                                                    synchronizer.name()
                                                );
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
                } catch (ExecutionException | InterruptedException | CancellationException e) {
                    dataSourceUpdates.updateStatus(DataSourceStatusProvider.State.INTERRUPTED,
                        new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.UNKNOWN,
                            0,
                            e.toString(),
                            new Date().toInstant()
                        ));
                    Throwable root = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    logger.error("Error running synchronizer '{}': {}",
                        synchronizer.name(),
                        root.getMessage() != null ? root.getMessage() : LogValues.exceptionSummary(root));
                    // Move to the next synchronizer.
                }
                // Get the next available synchronizer and set it active
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            }
            if (!closed) {
                logger.warn("No more synchronizers available.");
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
     * <p>{@link #getFuture()} returns a <em>fresh</em> {@link CompletableFuture}
     * per call rather than returning the same shared instance. This matters
     * because the run loop calls {@code CompletableFuture.anyOf(getFuture(),
     * synchronizerNext)} on every iteration: if {@code getFuture()} returned a
     * shared instance, each {@code anyOf} call would permanently attach an
     * {@code OrRelay} {@code Completion} to its {@code stack}. On a healthy
     * primary synchronizer that streams ChangeSets without ever arming the
     * fallback timer, the aggregate never completes, so those Completion nodes
     * accumulate monotonically for the synchronizer's full tenure -- a real
     * memory leak proportional to event rate.
     *
     * <p>The fix: a single permanent listener on the underlying aggregate fans
     * out completion to every fresh future handed out by {@link #getFuture()}.
     * Fresh futures are tracked via {@link WeakReference} on a pending list, so
     * a fresh future whose only strong references were in the caller's loop
     * iteration becomes garbage-collectable once that iteration ends. Pending
     * entries whose referent has been collected are pruned opportunistically on
     * subsequent {@code getFuture()} calls and on {@link #close()}.
     *
     * <p>Package-private (rather than private) so that direct unit tests can
     * exercise the API surface and assert per-call distinctness.
     */
    static class Conditions implements AutoCloseable {
        private final List<Condition> conditions;
        private final CompletableFuture<Object> aggregate;
        private final Object lock = new Object();

        /**
         * Set to {@code true} once the aggregate has completed (either
         * normally or exceptionally). {@code volatile} so the fast path in
         * {@link #getFuture()} avoids taking the lock. Set under {@code lock}
         * together with populating {@code firedResult}/{@code firedThrowable}
         * and clearing {@code pending}, so a reader that observes
         * {@code isFired == true} also observes the corresponding values via
         * the JMM happens-before edge.
         */
        private volatile boolean isFired;

        /**
         * Result value the aggregate completed with, or {@code null} if it
         * completed exceptionally. Only meaningful when {@code isFired} is
         * true. Written under {@code lock}; readable without the lock once
         * {@code isFired} has been observed true (volatile happens-before).
         */
        private Object firedResult;

        /**
         * Throwable the aggregate completed exceptionally with, or
         * {@code null} if it completed normally. Same visibility rules as
         * {@code firedResult}.
         */
        private Throwable firedThrowable;

        /**
         * Tracks futures previously returned by {@link #getFuture()} that have
         * not yet been completed. {@code null} once the aggregate has fired
         * (and all pending entries have been drained). Mutated only under
         * {@code lock}.
         */
        private List<WeakReference<CompletableFuture<Object>>> pending = new ArrayList<>();

        public Conditions(List<Condition> conditions) {
            this.conditions = conditions;
            this.aggregate = conditions.isEmpty()
                ? new CompletableFuture<>() // Never completes if no conditions
                : CompletableFuture.anyOf(
                    conditions.stream().map(Condition::execute).toArray(CompletableFuture[]::new));

            // Single permanent listener. This is the only Completion node ever
            // attached to aggregate.stack -- subsequent getFuture() calls do
            // not touch the aggregate at all.
            this.aggregate.whenComplete((result, throwable) -> {
                List<WeakReference<CompletableFuture<Object>>> snapshot;
                synchronized (lock) {
                    firedResult = result;
                    firedThrowable = throwable;
                    isFired = true;
                    snapshot = pending;
                    pending = null;
                }
                if (snapshot == null) {
                    return;
                }
                for (WeakReference<CompletableFuture<Object>> ref : snapshot) {
                    CompletableFuture<Object> cf = ref.get();
                    if (cf == null) {
                        continue; // Already GC'd -- nothing to complete.
                    }
                    if (throwable != null) {
                        cf.completeExceptionally(throwable);
                    } else {
                        cf.complete(result);
                    }
                }
            });
        }

        /**
         * Returns a fresh future that will complete when the underlying
         * aggregate condition fires, or an already-completed future (normal or
         * exceptional) if the aggregate has already fired by the time this
         * method is called.
         */
        public CompletableFuture<Object> getFuture() {
            if (isFired) {
                return makeCompletedFuture();
            }

            CompletableFuture<Object> fresh = new CompletableFuture<>();
            synchronized (lock) {
                if (pending == null) {
                    // Raced with aggregate completion. isFired is now
                    // guaranteed true and firedResult/firedThrowable are
                    // populated (set under lock before pending was nulled).
                    return makeCompletedFuture();
                }
                // Opportunistic prune of weak refs whose target has been
                // collected. Keeps pending bounded even if the aggregate never
                // fires.
                Iterator<WeakReference<CompletableFuture<Object>>> it = pending.iterator();
                while (it.hasNext()) {
                    if (it.next().get() == null) {
                        it.remove();
                    }
                }
                pending.add(new WeakReference<>(fresh));
            }
            return fresh;
        }

        public void inform(FDv2SourceResult result) {
            conditions.forEach(c -> c.inform(result));
        }

        @Override
        public void close() {
            conditions.forEach(Condition::close);
            synchronized (lock) {
                if (pending != null) {
                    pending.clear();
                }
            }
        }

        /**
         * Materializes a new already-completed CompletableFuture mirroring
         * whichever terminal state {@link #aggregate} reached. Caller must
         * have observed {@code isFired == true}.
         */
        private CompletableFuture<Object> makeCompletedFuture() {
            if (firedThrowable != null) {
                CompletableFuture<Object> cf = new CompletableFuture<>();
                cf.completeExceptionally(firedThrowable);
                return cf;
            }
            return CompletableFuture.completedFuture(firedResult);
        }
    }
}
