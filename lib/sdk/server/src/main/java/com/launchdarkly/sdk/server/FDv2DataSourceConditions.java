package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
            UNPARK,
            FDV1_FALLBACK_END,
        }

        CompletableFuture<Condition> execute();

        /**
         * Informs the condition of a result from the synchronizer.
         *
         * @param sourceResult the result
         * @return true if the condition should be replaced with a new one
         */
        boolean inform(FDv2SourceResult sourceResult);

        void close();

        ConditionType getType();
    }

    interface ConditionFactory {
        /**
         * Builds the condition.
         *
         * @return the condition, or null if the condition does not apply
         */
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
        protected final Duration timeout;

        public TimedCondition(ScheduledExecutorService sharedExecutor, Duration timeout) {
            this.sharedExecutor = sharedExecutor;
            this.timeout = timeout;
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
            protected final Duration timeout;

            public Factory(ScheduledExecutorService sharedExecutor, Duration timeout) {
                this.sharedExecutor = sharedExecutor;
                this.timeout = timeout;
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
            public Factory(ScheduledExecutorService sharedExecutor, Duration timeout) {
                super(sharedExecutor, timeout);
            }

            @Override
            public Condition build() {
                return new FallbackCondition(sharedExecutor, timeout);
            }

            @Override
            public ConditionType getType() {
                return ConditionType.FALLBACK;
            }
        }

        public FallbackCondition(ScheduledExecutorService sharedExecutor, Duration timeout) {
            super(sharedExecutor, timeout);
        }

        @Override
        public boolean inform(FDv2SourceResult sourceResult) {
            if (sourceResult.getResultType() == SourceResultType.CHANGE_SET) {
                // The synchronizer is healthy. If the timer has started, the condition must start over.
                return timerFuture != null;
            }
            if (sourceResult.getResultType() == SourceResultType.STATUS && sourceResult.getStatus().getState() == SourceSignal.INTERRUPTED) {
                if (timerFuture == null) {
                    timerFuture = sharedExecutor.schedule(() -> {
                        resultFuture.complete(this);
                        return null;
                    }, timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
            return false;
        }

        @Override
        public ConditionType getType() {
            return ConditionType.FALLBACK;
        }
    }

    static class RecoveryCondition extends TimedCondition {

        static class Factory extends TimedCondition.Factory {
            public Factory(ScheduledExecutorService sharedExecutor, Duration timeout) {
                super(sharedExecutor, timeout);
            }

            @Override
            public Condition build() {
                return new RecoveryCondition(sharedExecutor, timeout);
            }

            @Override
            public ConditionType getType() {
                return ConditionType.RECOVERY;
            }
        }

        public RecoveryCondition(ScheduledExecutorService sharedExecutor, Duration timeout) {
            super(sharedExecutor, timeout);
            timerFuture = sharedExecutor.schedule(() -> {
                resultFuture.complete(this);
                return null;
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean inform(FDv2SourceResult sourceResult) {
            // Recovery is time-based, so a result never requires a new condition.
            return false;
        }

        @Override
        public ConditionType getType() {
            return ConditionType.RECOVERY;
        }
    }

    /**
     * This condition is met when its delay has passed.
     */
    static class DeadlineCondition extends TimedCondition {
        /**
         * Builds a condition that is met at the deadline, or no condition if there is no deadline.
         */
        static class Factory implements ConditionFactory {
            private final ScheduledExecutorService sharedExecutor;
            private final ConditionType type;
            private final Supplier<Instant> deadline;

            public Factory(ScheduledExecutorService sharedExecutor, ConditionType type, Supplier<Instant> deadline) {
                this.sharedExecutor = sharedExecutor;
                this.type = type;
                this.deadline = deadline;
            }

            @Override
            public Condition build() {
                Instant time = deadline.get();
                if (time == null) {
                    return null;
                }
                return new DeadlineCondition(sharedExecutor, type, Duration.between(Instant.now(), time));
            }

            @Override
            public ConditionType getType() {
                return type;
            }
        }

        private final ConditionType type;

        public DeadlineCondition(ScheduledExecutorService sharedExecutor, ConditionType type, Duration delay) {
            super(sharedExecutor, delay);
            this.type = type;
            timerFuture = sharedExecutor.schedule(() -> {
                resultFuture.complete(this);
                return null;
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean inform(FDv2SourceResult sourceResult) {
            // A deadline is time-based, so a result never requires a new condition.
            return false;
        }

        @Override
        public ConditionType getType() {
            return type;
        }
    }
}
