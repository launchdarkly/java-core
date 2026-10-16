package com.launchdarkly.sdk.server.subsystems;

import java.io.Closeable;

/**
 * Supplies flag and segment overrides that take precedence over LaunchDarkly data at evaluation
 * time, on a per-key basis. Overrides exist for resilience during an incident. They let an operator
 * force one or more flags to a known state on a running client, whether or not the client can
 * reach LaunchDarkly.
 * <p>
 * An override source is not a data source. It does not take part in the data system's initializer
 * and synchronizer pipeline. The override layer that it populates has no effect on the client's
 * initialization status, data availability, or data source status.
 * <p>
 * To configure an override source, use
 * {@link com.launchdarkly.sdk.server.integrations.DataSystemBuilder#overrides(ComponentConfigurer)}.
 * The SDK provides a file-based source; see
 * {@link com.launchdarkly.sdk.server.integrations.FileOverrides}.
 * <p>
 * Flag overrides are currently experimental and subject to change.
 *
 * @since 7.18.0
 */
public interface OverrideSource extends Closeable {
  /**
   * Begins supplying overrides to the sink and returns without blocking on long-running work.
   * <p>
   * Implementations typically perform an initial load synchronously, then push a full replacement
   * snapshot to the sink whenever their backing data changes, until {@link #close()} is called. A
   * failed load should leave the previously supplied layer untouched by not calling the sink.
   * <p>
   * The SDK calls this method at most once, before any call to {@link #close()}, while the client
   * is being constructed.
   *
   * @param sink receives override snapshots
   */
  void start(OverrideSink sink);
}
