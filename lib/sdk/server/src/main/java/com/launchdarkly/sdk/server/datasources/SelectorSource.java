package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

public interface SelectorSource {
    Selector getSelector();
}
