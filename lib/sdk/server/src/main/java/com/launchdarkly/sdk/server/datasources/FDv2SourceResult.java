package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.io.Closeable;
import java.util.Map;
import java.util.function.Function;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * The result type for FDv2 initializers and synchronizers. An FDv2 initializer produces a single result, while
 * an FDv2 synchronizer produces a stream of results.
 */
public class FDv2SourceResult implements Closeable {

    /**
     * Represents a change in the status of the source.
     */
    public static class Status {
        private final SourceSignal state;
        private final DataSourceStatusProvider.ErrorInfo errorInfo;
        private final String reason;

        public SourceSignal getState() {
            return state;
        }

        public DataSourceStatusProvider.ErrorInfo getErrorInfo() {
            return errorInfo;
        }

        Status(SourceSignal state, DataSourceStatusProvider.ErrorInfo errorInfo, String reason) {
            this.state = state;
            this.errorInfo = errorInfo;
            this.reason = reason;
        }

        public static Status goodbye(String reason) {
            return new Status(SourceSignal.GOODBYE, null, reason);
        }

        public static Status interrupted(DataSourceStatusProvider.ErrorInfo errorInfo) {
            return new Status(SourceSignal.INTERRUPTED, errorInfo, null);
        }

        public static Status terminalError(DataSourceStatusProvider.ErrorInfo errorInfo) {
            return new Status(SourceSignal.TERMINAL_ERROR, errorInfo, null);
        }

        public static Status shutdown() {
            return new Status(SourceSignal.SHUTDOWN, null, null);
        }

        /**
         * If the state is GOODBYE, then this will be the reason. Otherwise, it will be null.
         * @return the reason, or null
         */
        public String getReason() {
            return reason;
        }
    }

    private final ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet;
    private final Status status;
    private final SourceResultType resultType;
    private final boolean fdv1Fallback;
    private final Function<Void, Void> completionCallback;

    private FDv2SourceResult(
        ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet,
        Status status, SourceResultType resultType,
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
            Status.interrupted(errorInfo),
            SourceResultType.STATUS,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult shutdown() {
        return shutdown(null);
    }

    public static FDv2SourceResult shutdown(Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(null,
            Status.shutdown(),
            SourceResultType.STATUS,
            false,
            completionCallback);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback) {
        return terminalError(errorInfo, fdv1Fallback, null);
    }

    public static FDv2SourceResult terminalError(DataSourceStatusProvider.ErrorInfo errorInfo, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(null,
            Status.terminalError(errorInfo),
            SourceResultType.STATUS,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult changeSet(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet, boolean fdv1Fallback) {
        return changeSet(changeSet, fdv1Fallback, null);
    }

    public static FDv2SourceResult changeSet(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(
            changeSet,
            null,
            SourceResultType.CHANGE_SET,
            fdv1Fallback,
            completionCallback);
    }

    public static FDv2SourceResult goodbye(String reason, boolean fdv1Fallback) {
        return goodbye(reason, fdv1Fallback, null);
    }

    public static FDv2SourceResult goodbye(String reason, boolean fdv1Fallback, Function<Void, Void> completionCallback) {
        return new FDv2SourceResult(
            null,
            Status.goodbye(reason),
            SourceResultType.STATUS,
            fdv1Fallback,
            completionCallback);
    }

    public SourceResultType getResultType() {
        return resultType;
    }

    public Status getStatus() {
        return status;
    }

    public ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> getChangeSet() {
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
        if (completionCallback != null) {
            completionCallback.apply(null);
        }
    }
}
