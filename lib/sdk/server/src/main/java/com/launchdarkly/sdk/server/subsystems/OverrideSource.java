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
 * The SDK closes the source when the client is closed. An exception thrown from {@link #close()} is
 * logged and does not stop the client from shutting down. A snapshot that the source supplies after
 * the client begins to close it is ignored.
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
   * is being constructed. This method is not intended to fail. A source that cannot load its data
   * logs the failure and keeps retrying, as the SDK's file-based source does. A configuration error
   * belongs in the {@link ComponentConfigurer#build(ClientContext)} that builds the source, where it
   * is reported when the client is created. If this method does throw, the client still starts: the
   * SDK logs the exception, closes the source, removes any snapshot the source supplied, and the
   * client runs without overrides.
   *
   * @param sink receives override snapshots
   */
  void start(OverrideSink sink);
}
