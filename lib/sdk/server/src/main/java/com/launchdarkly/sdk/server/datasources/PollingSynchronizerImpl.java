package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.CompletableFuture;
class PollingSynchronizerImpl extends PollingBase implements Synchronizer {
    public PollingSynchronizerImpl(FDv2Requestor requestor, LDLogger logger) {
        super(requestor, logger);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        return null;
    }

    @Override
    public void shutdown() {

    }
}
