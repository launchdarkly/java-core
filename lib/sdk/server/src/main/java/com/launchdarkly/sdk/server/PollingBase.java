package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ChangeSet;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpErrors;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.SerializationException;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

import static com.launchdarkly.sdk.internal.http.HttpErrors.*;

class PollingBase {
    private final FDv2Requestor requestor;
    private final LDLogger logger;

    public PollingBase(FDv2Requestor requestor, LDLogger logger) {
        this.requestor = requestor;
        this.logger = logger;
    }

    protected void internalShutdown() {
        requestor.close();
    }

    protected CompletableFuture<FDv2SourceResult> poll(Selector selector, boolean oneShot) {
        return requestor.Poll(selector).handle(((pollingResponse, ex) -> {
            if (ex != null) {
                if (ex instanceof HttpErrors.HttpErrorException) {
                    HttpErrors.HttpErrorException e = (HttpErrors.HttpErrorException) ex;
                    DataSourceStatusProvider.ErrorInfo errorInfo = DataSourceStatusProvider.ErrorInfo.fromHttpError(e.getStatus());
                    boolean recoverable = e.getStatus() > 0 && !isHttpErrorRecoverable(e.getStatus());
                    logger.error("Polling request failed with HTTP error: {}", e.getStatus());
                    // For a one-shot request all errors are terminal.
                    if (oneShot) {
                        return FDv2SourceResult.terminalError(errorInfo);
                    } else {
                        return recoverable ? FDv2SourceResult.interrupted(errorInfo) : FDv2SourceResult.terminalError(errorInfo);
                    }
                } else if (ex instanceof IOException) {
                    IOException e = (IOException) ex;
                    logger.error("Polling request failed with network error: {}", e.toString());
                    DataSourceStatusProvider.ErrorInfo info = new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.NETWORK_ERROR,
                            0,
                            e.toString(),
                            new Date().toInstant()
                    );
                    return oneShot ? FDv2SourceResult.terminalError(info) : FDv2SourceResult.interrupted(info);
                } else if (ex instanceof SerializationException) {
                    SerializationException e = (SerializationException) ex;
                    logger.error("Polling request received malformed data: {}", e.toString());
                    DataSourceStatusProvider.ErrorInfo info = new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.INVALID_DATA,
                            0,
                            e.toString(),
                            new Date().toInstant()
                    );
                    return oneShot ? FDv2SourceResult.terminalError(info) : FDv2SourceResult.interrupted(info);
                }
                Exception e = (Exception) ex;
                logger.error("Polling request failed with an unknown error: {}", e.toString());
                DataSourceStatusProvider.ErrorInfo info = new DataSourceStatusProvider.ErrorInfo(
                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        new Date().toInstant()
                );
                return oneShot ? FDv2SourceResult.terminalError(info) : FDv2SourceResult.interrupted(info);
            }
            FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
            for (FDv2Event event : pollingResponse.getEvents()) {
                FDv2ProtocolHandler.IFDv2ProtocolAction res = handler.handleEvent(event);
                switch (res.getAction()) {
                    case CHANGESET:
                        return FDv2SourceResult.changeSet(((FDv2ProtocolHandler.FDv2ActionChangeset) res).getChangeset());
                    case ERROR:
                        FDv2ProtocolHandler.FDv2ActionError error = ((FDv2ProtocolHandler.FDv2ActionError) res);
                        return FDv2SourceResult.terminalError(
                                new DataSourceStatusProvider.ErrorInfo(
                                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                                        0,
                                        error.getReason(),
                                        new Date().toInstant()));
                    case GOODBYE:
                        return FDv2SourceResult.goodbye(((FDv2ProtocolHandler.FDv2ActionGoodbye) res).getReason());
                    case NONE:
                        break;
                    case INTERNAL_ERROR:
                        return FDv2SourceResult.terminalError(
                                new DataSourceStatusProvider.ErrorInfo(
                                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                                        0,
                                        "Internal error occurred during polling",
                                        new Date().toInstant()));
                }
            }
            return FDv2SourceResult.terminalError(new DataSourceStatusProvider.ErrorInfo(
                    DataSourceStatusProvider.ErrorKind.UNKNOWN,
                    0,
                    "Unexpected end of polling response",
                    new Date().toInstant()
            ));
        }));
    }
}
