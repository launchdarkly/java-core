package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;

import java.time.Duration;

/**
 * Passed to {@link Hook#initializationCompleted(InitializationContext)} once, when the data
 * system first has data or determines that it cannot obtain data.
 *
 * @since 7.18.0
 */
public final class InitializationContext {
  private final DataSourceDescriptor dataSource;
  private final Duration duration;
  private final boolean succeeded;

  /**
   * Creates an instance.
   *
   * @param dataSource the component that provided the data; when the SDK did not obtain data, the
   *   last component that was tried
   * @param duration the time from the start of the data system to the end of initialization
   * @param succeeded true if the SDK has data
   */
  public InitializationContext(DataSourceDescriptor dataSource, Duration duration, boolean succeeded) {
    this.dataSource = dataSource == null ? DataSourceDescriptor.empty() : dataSource;
    this.duration = duration;
    this.succeeded = succeeded;
  }

  /**
   * @return the component that provided the data, or the last component that was tried
   */
  public DataSourceDescriptor getDataSource() {
    return dataSource;
  }

  /**
   * @return the time from the start of the data system to the end of initialization
   */
  public Duration getDuration() {
    return duration;
  }

  /**
   * @return true if the SDK has data; false if the SDK serves default values
   */
  public boolean isSucceeded() {
    return succeeded;
  }
}
