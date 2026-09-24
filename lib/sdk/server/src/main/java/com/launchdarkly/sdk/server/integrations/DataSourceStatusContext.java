package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

/**
 * Passed to {@link Hook#dataSourceStatusChanged(DataSourceStatusContext)} when the data source
 * status changes.
 *
 * @since 7.18.0
 */
public final class DataSourceStatusContext {
  private final DataSourceStatusProvider.Status previous;
  private final DataSourceStatusProvider.Status current;

  /**
   * Creates an instance.
   *
   * @param previous the status before the change, or null for the first status the SDK reports
   * @param current the status after the change
   */
  public DataSourceStatusContext(DataSourceStatusProvider.Status previous,
      DataSourceStatusProvider.Status current) {
    this.previous = previous;
    this.current = current;
  }

  /**
   * @return the status before the change, or null for the first status the SDK reports
   */
  public DataSourceStatusProvider.Status getPrevious() {
    return previous;
  }

  /**
   * @return the status after the change
   */
  public DataSourceStatusProvider.Status getCurrent() {
    return current;
  }
}
