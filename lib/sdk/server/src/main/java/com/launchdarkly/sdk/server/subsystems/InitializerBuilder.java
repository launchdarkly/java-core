package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.datasources.Initializer;

public interface InitializerBuilder {
    Initializer build(DataSourceBuilderContext context);
}
