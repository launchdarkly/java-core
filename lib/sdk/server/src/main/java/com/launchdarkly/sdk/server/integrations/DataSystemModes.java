package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration;

/**
 * A set of different data system modes which provide pre-configured {@link DataSystemBuilder}s.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * </p>
 * <p>
 * This implementation is non-static to allow for easy usage with "Components".
 * Where we can return an instance of this object, and the user can chain into their desired configuration.
 * </p>
 */
public final class DataSystemModes {
  // This implementation is non-static to allow for easy usage with "Components".
  // Where we can return an instance of this object, and the user can chain into their desired configuration.

  /**
   * Configure's LaunchDarkly's recommended flag data acquisition strategy.
   * <p>
   * Currently, it operates a two-phase method for getting data: first, it requests data from LaunchDarkly's
   * global CDN. Then, it initiates a streaming connection to LaunchDarkly's Flag Delivery services to receive
   * real-time updates. If the streaming connection is interrupted for an extended period of time, the SDK will
   * automatically fall back to polling the global CDN for updates.
   * </p>
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem().defaultMode());
   * </code></pre>
   * 
   * @return a builder containing our default configuration
   */
  public DataSystemBuilder defaultMode() {
    return custom()
        .initializers(DataSystemComponents.pollingInitializer())
        .synchronizers(DataSystemComponents.streamingSynchronizer(), DataSystemComponents.pollingSynchronizer())
        .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling());
  }

  /**
   * Configures the SDK to stream data without polling for the initial payload.
   * <p>
   * This is not our recommended strategy, which is {@link #defaultMode()}, but it may be
   * suitable for some situations.
   * </p>
   * <p>
   * This configuration will not automatically fall back to polling, but it can be instructed by LaunchDarkly
   * to fall back to polling in certain situations.
   * </p>
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem().streaming());
   * </code></pre>
   * 
   * @return a builder containing a primarily streaming configuration
   */
  public DataSystemBuilder streaming() {
    return custom()
        .synchronizers(DataSystemComponents.streamingSynchronizer())
        .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling());
  }

  /**
   * Configure the SDK to poll data instead of receiving real-time updates via a stream.
   * <p>
   * This is not our recommended strategy, which is {@link #defaultMode()}, but it may be
   * required for certain network configurations.
   * </p>
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem().polling());
   * </code></pre>
   * 
   * @return a builder containing a polling-only configuration
   */
  public DataSystemBuilder polling() {
    return custom()
        .synchronizers(DataSystemComponents.pollingSynchronizer())
        .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling());
  }

  /**
   * Configures the SDK to read from a persistent store integration that is populated by Relay Proxy
   * or other SDKs. The SDK will not connect to LaunchDarkly. In this mode, the SDK never writes to the data
   * store.
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem().daemon(persistentStore));
   * </code></pre>
   * 
   * @param persistentStore the persistent store configurer
   * @return a builder which is configured for daemon mode
   */
  public DataSystemBuilder daemon(ComponentConfigurer<DataStore> persistentStore) {
    return custom()
        .persistentStore(persistentStore, DataSystemConfiguration.DataStoreMode.READ_ONLY);
  }

  /**
   * PersistentStore is similar to Default, with the addition of a persistent store integration. Before data has
   * arrived from LaunchDarkly, the SDK is able to evaluate flags using data from the persistent store.
   * Once fresh data is available, the SDK will no longer read from the persistent store, although it will keep
   * it up to date.
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem()
   *         .persistentStore(Components.persistentDataStore(SomeDatabaseName.dataStore())));
   * </code></pre>
   * 
   * @param persistentStore the persistent store configurer
   * @return a builder which is configured for persistent store mode
   */
  public DataSystemBuilder persistentStore(ComponentConfigurer<DataStore> persistentStore) {
    return defaultMode()
        .persistentStore(persistentStore, DataSystemConfiguration.DataStoreMode.READ_WRITE);
  }

  /**
   * Custom returns a builder suitable for creating a custom data acquisition strategy. You may configure
   * how the SDK uses a Persistent Store, how the SDK obtains an initial set of data, and how the SDK keeps data
   * up to date.
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder("my-sdk-key")
   *       .dataSystem(Components.dataSystem().custom()
   *         .initializers(DataSystemComponents.pollingInitializer())
   *         .synchronizers(DataSystemComponents.streamingSynchronizer(), DataSystemComponents.pollingSynchronizer())
   *         .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
   * </code></pre>
   * 
   * @return a builder without any base configuration
   */
  public DataSystemBuilder custom() {
    return new DataSystemBuilder();
  }
}

