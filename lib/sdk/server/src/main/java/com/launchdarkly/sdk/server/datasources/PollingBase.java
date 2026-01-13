package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.logging.LDLogger;

class PollingBase {
    private final FDv2Requestor requestor;
    private final LDLogger logger;

    public PollingBase(FDv2Requestor requestor, LDLogger logger) {
        this.requestor = requestor;
        this.logger = logger;
    }

    private FDv2SourceResult Poll() {
        return null;
    }
}
