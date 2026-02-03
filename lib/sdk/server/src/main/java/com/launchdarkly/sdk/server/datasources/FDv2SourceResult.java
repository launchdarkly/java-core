package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;

import java.io.Closeable;
import java.util.function.Function;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * The result type for FDv2 initializers and synchronizers. An FDv2 initializer produces a single result, while
 * an FDv2 synchronizer produces a stream of results.
 */
public class FDv2SourceResult implements Closeable {

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

    private final DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet;
    private final Status status;

    private final ResultType resultType;

    private final boolean fdv1Fallback;

    private final Function<Void, Void> completionCallback;

    private FDv2SourceResult(
        DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet,
        Status status, ResultType resultType,
        boolean fdv1Fallback,
        Function<Void, Void> completionCallback
    ) {
        this.changeSet = changeSet;
        this.status = status;
        this.resultType = resultType;
        this.fdv1Fallback = fdv1Fallback;
        this.completionCallback = completionCallback;
    }

    public static FDv2SourceResult interrupted(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback) {
        return interrupted(errorInfo, fdv1Fallback, null);
    }

    public static FDv2SourceResult interrupted(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(
            null,
            new Status(State.INTERRUPTED, errorInfo),
            ResultType.STATUS,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult shutdown() {
        return shutdown(null);
    }

    public static FDv2SourceResult shutdown(Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(null,
            new Status(State.SHUTDOWN, null),
            ResultType.STATUS,
            false,
            completionCallback);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback) {
        return terminalError(errorInfo, fdv1Fallback, null);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(null,
            new Status(State.TERMINAL_ERROR, errorInfo),
            ResultType.STATUS,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult changeSet(DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet, boolean fdv1Fallback) {
        return changeSet(changeSet, fdv1Fallback, null);
    }

    public static FDv2SourceResult changeSet(DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> changeSet, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(
            changeSet,
            null,
            ResultType.CHANGE_SET,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult goodbye(String reason, boolean fdv1Fallback) {
        return goodbye(reason, fdv1Fallback, null);
    }

    public static FDv2SourceResult goodbye(String reason, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        // TODO: Goodbye reason.
        return new FDv2SourceResult(
            null,
            new Status(State.GOODBYE, null),
            ResultType.STATUS,
            fdv1Fallback,
            completionCallback);
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

    /**
     * Creates a new result wrapping this one with an additional completion callback.
     * <p>
     * The new completion callback will be invoked when the result is closed, followed by
     * the original completion callback (if any).
     *
     * @param newCallback the completion callback to add
     * @return a new FDv2SourceResult with the added completion callback
     */
    public FDv2SourceResult withCompletion(Function<Void, Void> newCallback) {
        Function<Void, Void> combinedCallback = v -> {
            newCallback.apply(null);
            if (completionCallback != null) {
                completionCallback.apply(null);
            }
            return null;
        };
        return new FDv2SourceResult(changeSet, status, resultType, fdv1Fallback, combinedCallback);
    }

    @Override
    public void close() {
        if(completionCallback != null) {
            completionCallback.apply(null);
        }
    }
}
