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
   * Get a builder for a polling initializer.
   *
   * @return the polling initializer builder
   */
  public static FDv2PollingInitializerBuilder pollingInitializer() {
    return new FDv2PollingInitializerBuilder();
  }

  /**
   * Get a builder for a polling synchronizer.
   *
   * @return the polling synchronizer builder
   */
  public static FDv2PollingSynchronizerBuilder pollingSynchronizer() {
    return new FDv2PollingSynchronizerBuilder();
  }

  /**
   * Get a builder for a streaming synchronizer.
   *
   * @return the streaming synchronizer builder
   */
  public static FDv2StreamingSynchronizerBuilder streamingSynchronizer() {
    return new FDv2StreamingSynchronizerBuilder();
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

