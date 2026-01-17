package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.SelectorSource;

public interface InitializerBuilder {
    Initializer build(ClientContext context, SelectorSource selectorSource);
}
