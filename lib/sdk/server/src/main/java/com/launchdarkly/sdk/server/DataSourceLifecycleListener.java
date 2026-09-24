package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.integrations.InitializationContext;
import com.launchdarkly.sdk.server.integrations.InitializerContext;
import com.launchdarkly.sdk.server.integrations.SynchronizerChangeContext;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

/**
 * Receives the data source lifecycle events that the SDK reports to hooks. The data systems call
 * these methods in the order in which the events occur, on the thread that produces them.
 */
interface DataSourceLifecycleListener {
  void dataSourceStatusChanged(DataSourceStatusProvider.Status previous, DataSourceStatusProvider.Status current);

  void initializerCompleted(InitializerContext initializerContext);

  void synchronizerChanged(SynchronizerChangeContext changeContext);

  void initializationCompleted(InitializationContext initializationContext);
}
