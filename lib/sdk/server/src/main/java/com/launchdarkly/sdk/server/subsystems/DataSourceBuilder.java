package com.launchdarkly.sdk.server.subsystems;


/**
 * Interface for building synchronizers and initializers.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature, please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * @param <TDataSource>
 */
public interface DataSourceBuilder<TDataSource> {
  /**
   * Builds a data source instance based on the provided context.
   *
   * @param context the context for building the data source
   * @return the built data source instance
   */
  TDataSource build(DataSourceBuilderContext context);
}
