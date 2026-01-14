package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.Components;

/**
 * Components for use with the data system.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * </p>
 */
public final class DataSystemComponents {
  
  private DataSystemComponents() {}
  
  /**
   * Get a builder for a polling data source.
   * 
   * @return the polling data source builder
   */
  public static FDv2PollingDataSourceBuilder polling() {
    return new FDv2PollingDataSourceBuilder();
  }

  /**
   * Get a builder for a streaming data source.
   * 
   * @return the streaming data source builder
   */
  public static FDv2StreamingDataSourceBuilder streaming() {
    return new FDv2StreamingDataSourceBuilder();
  }

  /**
   * Get a builder for a FDv1 compatible polling data source.
   * <p>
   * This is intended for use as a fallback.
   * </p>
   * 
   * @return the FDv1 compatible polling data source builder
   */
  public static PollingDataSourceBuilder fDv1Polling() {
    return Components.pollingDataSource();
  }
}

