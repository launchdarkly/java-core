package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;

import java.util.concurrent.Executors;

/**
 * Test helper for creating DataSourceBuildInputs for FDv2 initializer and synchronizer tests.
 */
class TestDataSourceBuildInputs {
    static DataSourceBuildInputs create(LDLogger logger) {
        ServiceEndpoints endpoints = Components.serviceEndpoints().createServiceEndpoints();
        return new DataSourceBuildInputs(
                logger,
                Thread.NORM_PRIORITY,
                null,  // dataSourceUpdates not needed for these tests
                endpoints,
                null,  // http not needed for these tests
                Executors.newSingleThreadScheduledExecutor(),
                null,  // diagnosticStore not needed
                () -> Selector.EMPTY  // SelectorSource returning empty selector
        );
    }
}
