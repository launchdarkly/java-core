package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.SelectorSource;

import java.util.concurrent.CompletableFuture;

class PollingInitializerImpl extends PollingBase implements Initializer {
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final SelectorSource selectorSource;

    public PollingInitializerImpl(FDv2Requestor requestor, LDLogger logger, SelectorSource selectorSource) {
        super(requestor, logger);
        this.selectorSource = selectorSource;
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
