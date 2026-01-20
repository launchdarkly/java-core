package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.datasources.Synchronizer;

public interface SynchronizerBuilder {
    Synchronizer build(DataSourceBuilderContext context);
}
