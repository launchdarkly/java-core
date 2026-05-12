package com.launchdarkly.sdk.server.subsystems;


/**
 * Interface for building synchronizers and initializers.
 * @param <TDataSource>
 */
public interface DataSourceBuilder<TDataSource> {
  /**
   * Builds a data source instance based on the provided context.
   *
   * @param context the context for building the data source
   * @return the built data source instance
   */
  TDataSource build(DataSourceBuildInputs context);
}
