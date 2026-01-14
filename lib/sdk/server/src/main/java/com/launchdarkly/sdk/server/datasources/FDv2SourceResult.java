package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * The result type for FDv2 initializers and synchronizers. An FDv2 initializer produces a single result, while
 * an FDv2 synchronizer produces a stream of results.
 */
public class FDv2SourceResult {
    public enum State {
        /**
         * The data source has encountered an interruption and will attempt to reconnect.
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

        public State getState() {
            return state;
        }

        public DataSourceStatusProvider.ErrorInfo getErrorInfo() {
            return errorInfo;
        }

        public Status(State state, DataSourceStatusProvider.ErrorInfo errorInfo) {
            this.state = state;
            this.errorInfo = errorInfo;
        }
    }

    private final FDv2ChangeSet changeSet;
    private final Status status;

    private final ResultType resultType;

    private FDv2SourceResult(FDv2ChangeSet changeSet, Status status, ResultType resultType) {
        this.changeSet = changeSet;
        this.status = status;
        this.resultType = resultType;
    }

    public static FDv2SourceResult interrupted(DataSourceStatusProvider.ErrorInfo errorInfo) {
        return new FDv2SourceResult(null, new Status(State.INTERRUPTED, errorInfo), ResultType.STATUS);
    }

    public static FDv2SourceResult shutdown() {
        return new FDv2SourceResult(null, new Status(State.SHUTDOWN, null), ResultType.STATUS);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo) {
        return new FDv2SourceResult(null, new Status(State.TERMINAL_ERROR, errorInfo), ResultType.STATUS);
    }

    public static FDv2SourceResult changeSet(FDv2ChangeSet changeSet) {
        return new FDv2SourceResult(changeSet, null, ResultType.CHANGE_SET);
    }

    public static FDv2SourceResult goodbye(String reason) {
        // TODO: Goodbye reason.
        return new FDv2SourceResult(null, new Status(State.GOODBYE, null), ResultType.STATUS);
    }

    public ResultType getResultType() {
        return resultType;
    }

    public Status getStatus() {
        return status;
    }

    public FDv2ChangeSet getChangeSet() {
        return changeSet;
    }
}
