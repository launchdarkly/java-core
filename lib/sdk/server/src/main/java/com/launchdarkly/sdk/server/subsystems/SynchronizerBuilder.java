package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;

public interface SynchronizerBuilder {
    Synchronizer build(ClientContext context, SelectorSource selectorSource);
}
