package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.datasources.SelectorSource;

import java.util.concurrent.CompletableFuture;

class PollingInitializerImpl extends PollingBase implements Initializer {
    static final String DEFAULT_NAME = "polling";
    private final String name;
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final SelectorSource selectorSource;

    public PollingInitializerImpl(FDv2Requestor requestor, LDLogger logger, SelectorSource selectorSource) {
        this(requestor, logger, selectorSource, null);
    }

    public PollingInitializerImpl(FDv2Requestor requestor, LDLogger logger, SelectorSource selectorSource, String name) {
        super(requestor, logger.subLogger(Loggers.POLLING_INITIALIZER));
        this.name = name == null || name.isEmpty() ? DEFAULT_NAME : name;
        this.selectorSource = selectorSource;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DataSourceDescriptor describe() {
        return DataSourceDescriptor.of(DataSourceDescriptor.Protocol.FDV2, DataSourceDescriptor.Transport.POLLING, name);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> run() {
        CompletableFuture<FDv2SourceResult> pollResult = poll(selectorSource.getSelector(), true);
        return CompletableFuture.anyOf(shutdownFuture, pollResult)
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close() {
        shutdownFuture.complete(FDv2SourceResult.shutdown());
        internalShutdown();
    }
}
