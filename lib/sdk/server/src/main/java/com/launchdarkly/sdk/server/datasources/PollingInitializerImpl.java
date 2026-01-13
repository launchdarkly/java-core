package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.CompletableFuture;

class PollingInitializerImpl extends PollingBase implements Initializer {

    public PollingInitializerImpl(FDv2Requestor requestor, LDLogger logger) {
        super(requestor, logger);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> run() {
        return null;
    }

    @Override
    public void shutdown() {

    }
}
