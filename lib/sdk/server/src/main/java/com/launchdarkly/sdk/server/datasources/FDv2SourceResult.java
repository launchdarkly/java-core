package com.launchdarkly.sdk.server.datasources;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * The result type for FDv2 initializers and synchronizers. An FDv2 initializer produces a single result, while
 * an FDv2 synchronizer produces a stream of results.
 */
public class FDv2SourceResult {
    public enum State {
        /**
         * The data source has encountered an interruption and will attempt to reconnect. This isn't intended to be used
         * with an initializer, and instead TERMINAL_ERROR should be used. When this status is used with an initializer,
         * it will still be a terminal state.
         */
        INTERRUPTED,
        /**
         * The data source has been shut down and will not produce any further results.
         */
        SHUTDOWN,
        /**
         * The data source has encountered a terminal error and will not produce any further results.
         */
        TERMINAL_ERROR,
        /**
         * The data source has been instructed to disconnect and will not produce any further results.
         */
        GOODBYE,
    }

    public enum ResultType {
        /**
         * The source has emitted a change set. This implies that the source is valid.
         */
        CHANGE_SET,
        /**
         * The source is emitting a status which indicates a transition from being valid to being in some kind
         * of error state. The source will emit a CHANGE_SET if it becomes valid again.
         */
        STATUS,
    }

    /**
     * Represents a change in the status of the source.
     */
    public static class Status {
        private final State state;
        private final DataSourceStatusProvider.ErrorInfo errorInfo;

        private final String reason;

        public State getState() {
            return state;
        }

        public DataSourceStatusProvider.ErrorInfo getErrorInfo() {
            return errorInfo;
        }

        Status(State state, DataSourceStatusProvider.ErrorInfo errorInfo, String reason) {
            this.state = state;
            this.errorInfo = errorInfo;
            this.reason = reason;
        }

        public static Status goodbye(String reason) {
            return new Status(State.GOODBYE, null, reason);
        }

        public static Status interrupted(DataSourceStatusProvider.ErrorInfo errorInfo) {
            return new Status(State.INTERRUPTED, errorInfo, null);
        }

        public static Status terminalError(DataSourceStatusProvider.ErrorInfo errorInfo) {
            return new Status(State.TERMINAL_ERROR, errorInfo, null);
        }

        public static Status shutdown() {
            return new Status(State.SHUTDOWN, null, null);
        }

        /**
         * If the state is GOODBYE, then this will be the reason. Otherwise, it will be null.
         * @return the reason, or null
         */
        public String getReason() {
            return reason;
        }
    }

    private final DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet;
    private final Status status;

    private final ResultType resultType;
    
    private final boolean fdv1Fallback;

    private FDv2SourceResult(DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet, Status status, ResultType resultType, boolean fdv1Fallback) {
        this.changeSet = changeSet;
        this.status = status;
        this.resultType = resultType;
        this.fdv1Fallback = fdv1Fallback;
    }

    public static FDv2SourceResult interrupted(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback) {
        return new FDv2SourceResult(
          null,
          Status.interrupted(errorInfo),
          ResultType.STATUS,
          fdv1Fallback);
    }

    public static FDv2SourceResult shutdown() {
        return new FDv2SourceResult(null,
          Status.shutdown(),
          ResultType.STATUS,
          false);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback) {
        return new FDv2SourceResult(null,
          Status.terminalError(errorInfo),
          ResultType.STATUS,
          fdv1Fallback);
    }

    public static FDv2SourceResult changeSet(DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet, boolean fdv1Fallback) {
        return new FDv2SourceResult(changeSet, null, ResultType.CHANGE_SET, fdv1Fallback);
    }

    public static FDv2SourceResult goodbye(String reason, boolean fdv1Fallback) {
        return new FDv2SourceResult(
          null,
          Status.goodbye(reason),
          ResultType.STATUS,
          fdv1Fallback);
    }

    public ResultType getResultType() {
        return resultType;
    }

    public Status getStatus() {
        return status;
    }

    public DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> getChangeSet() {
        return changeSet;
    }

    public boolean isFdv1Fallback() {
        return fdv1Fallback;
    }
}
