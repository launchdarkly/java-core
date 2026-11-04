package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Container class for FDv2 data source conditions and related types.
 * <p>
 * This class is non-constructable and serves only as a namespace for condition-related types.
 * Package-private for internal use and testing.
 */
class FDv2DataSourceConditions {
    /**
     * Private constructor to prevent instantiation.
     */
    private FDv2DataSourceConditions() {
    }

    /**
     * Package-private for testing.
     */
    interface Condition extends Closeable {
        enum ConditionType {
            FALLBACK,
            RECOVERY,
        }

        CompletableFuture<Condition> execute();

        void inform(FDv2SourceResult sourceResult);

        void close();

        ConditionType getType();
    }

    interface ConditionFactory {
        Condition build();

        Condition.ConditionType getType();
    }

    static abstract class TimedCondition implements Condition {
        protected final CompletableFuture<Condition> resultFuture = new CompletableFuture<>();

        protected final ScheduledExecutorService sharedExecutor;

        /**
         * Future for the timeout task, if any. Will be null when no timeout is active.
         */
        protected ScheduledFuture<Void> timerFuture;

        /**
         * Timeout duration for the fallback operation.
         */
        protected final long timeoutSeconds;

        public TimedCondition(ScheduledExecutorService sharedExecutor, long timeoutSeconds) {
            this.sharedExecutor = sharedExecutor;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public CompletableFuture<Condition> execute() {
            return resultFuture;
        }

        @Override
        public void close() {
            if (timerFuture != null) {
                timerFuture.cancel(false);
                timerFuture = null;
            }
        }

        static abstract class Factory implements ConditionFactory {
            protected final ScheduledExecutorService sharedExecutor;
            protected final long timeoutSeconds;

            public Factory(ScheduledExecutorService sharedExecutor, long timeout) {
                this.sharedExecutor = sharedExecutor;
                this.timeoutSeconds = timeout;
            }
        }
    }

    /**
     * This condition is used to determine if a fallback should be performed. It is informed of each data source result
     * via {@link #inform(FDv2SourceResult)}. Based on the results, it updates its internal state. When the fallback
     * condition is met, then the {@link java.util.concurrent.Future} returned by {@link #execute()} will complete.
     * <p>
     * This is package-private, instead of private, for ease of testing.
     */
    static class FallbackCondition extends TimedCondition {
        static class Factory extends TimedCondition.Factory {
            private final long initializationTimeoutSeconds;
            private final BooleanSupplier dataApplied;

            public Factory(ScheduledExecutorService sharedExecutor, long timeout) {
                this(sharedExecutor, timeout, 0, () -> true);
            }

            /**
             * @param initializationTimeoutSeconds how long a synchronizer may go without delivering data, while no
             *                                     data has been applied yet, before falling back
             * @param dataApplied                  reports whether the data source has applied any data yet
             */
            public Factory(
                ScheduledExecutorService sharedExecutor,
                long timeout,
                long initializationTimeoutSeconds,
                BooleanSupplier dataApplied
            ) {
                super(sharedExecutor, timeout);
                this.initializationTimeoutSeconds = initializationTimeoutSeconds;
                this.dataApplied = dataApplied;
            }

            @Override
            public Condition build() {
                if (dataApplied.getAsBoolean()) {
                    return new FallbackCondition(sharedExecutor, timeoutSeconds);
                }
                return new FallbackCondition(sharedExecutor, timeoutSeconds, initializationTimeoutSeconds);
            }

            @Override
            public ConditionType getType() {
                return ConditionType.FALLBACK;
            }
        }

        public FallbackCondition(ScheduledExecutorService sharedExecutor, long timeoutSeconds) {
            super(sharedExecutor, timeoutSeconds);
        }

        /**
         * Creates a fallback condition for use before any data has been applied. A synchronizer that never connects
         * may not report any status for a long time, so the timer starts immediately instead of waiting for an
         * interruption. Receiving a change set cancels it.
         */
        public FallbackCondition(
            ScheduledExecutorService sharedExecutor,
            long timeoutSeconds,
            long initializationTimeoutSeconds
        ) {
            super(sharedExecutor, timeoutSeconds);
            timerFuture = sharedExecutor.schedule(() -> {
                resultFuture.complete(this);
                return null;
            }, initializationTimeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void inform(FDv2SourceResult sourceResult) {
            if (sourceResult.getResultType() == SourceResultType.CHANGE_SET) {
                if (timerFuture != null) {
                    timerFuture.cancel(false);
                    timerFuture = null;
                }
            }
            if (sourceResult.getResultType() == SourceResultType.STATUS && sourceResult.getStatus().getState() == SourceSignal.INTERRUPTED) {
                if (timerFuture == null) {
                    timerFuture = sharedExecutor.schedule(() -> {
                        resultFuture.complete(this);
                        return null;
                    }, timeoutSeconds, TimeUnit.SECONDS);
                }
            }
        }

        @Override
        public ConditionType getType() {
            return ConditionType.FALLBACK;
        }
    }

    static class RecoveryCondition extends TimedCondition {

        static class Factory extends TimedCondition.Factory {
            public Factory(ScheduledExecutorService sharedExecutor, long timeout) {
                super(sharedExecutor, timeout);
            }

            @Override
            public Condition build() {
                return new RecoveryCondition(sharedExecutor, timeoutSeconds);
            }

            @Override
            public ConditionType getType() {
                return ConditionType.RECOVERY;
            }
        }

        public RecoveryCondition(ScheduledExecutorService sharedExecutor, long timeoutSeconds) {
            super(sharedExecutor, timeoutSeconds);
            timerFuture = sharedExecutor.schedule(() -> {
                resultFuture.complete(this);
                return null;
            }, timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void inform(FDv2SourceResult sourceResult) {
            // Time-based recovery.
        }

        @Override
        public ConditionType getType() {
            return ConditionType.RECOVERY;
        }
    }
}
