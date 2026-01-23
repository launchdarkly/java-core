package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

        void close() throws IOException;

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
        public void close() throws IOException {
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
            public Factory(ScheduledExecutorService sharedExecutor, long timeout) {
                super(sharedExecutor, timeout);
            }

            @Override
            public Condition build() {
                return new FallbackCondition(sharedExecutor, timeoutSeconds);
            }

            @Override
            public ConditionType getType() {
                return ConditionType.FALLBACK;
            }
        }

        public FallbackCondition(ScheduledExecutorService sharedExecutor, long timeoutSeconds) {
            super(sharedExecutor, timeoutSeconds);
        }

        @Override
        public void inform(FDv2SourceResult sourceResult) {
            if (sourceResult == null) {
                return;
            }
            if (sourceResult.getResultType() == FDv2SourceResult.ResultType.CHANGE_SET) {
                if (timerFuture != null) {
                    timerFuture.cancel(false);
                    timerFuture = null;
                }
            }
            if (sourceResult.getResultType() == FDv2SourceResult.ResultType.STATUS && sourceResult.getStatus().getState() == FDv2SourceResult.State.INTERRUPTED) {
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