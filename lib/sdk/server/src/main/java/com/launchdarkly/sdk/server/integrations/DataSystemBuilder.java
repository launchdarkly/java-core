package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration builder for the SDK's data acquisition and storage strategy.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * </p>
 */
public final class DataSystemBuilder {

  private final List<ComponentConfigurer<DataSource>> initializers = new ArrayList<>();
  private final List<ComponentConfigurer<DataSource>> synchronizers = new ArrayList<>();
  private ComponentConfigurer<DataSource> fDv1FallbackSynchronizer;
  private ComponentConfigurer<DataStore> persistentStore;
  private DataSystemConfiguration.DataStoreMode persistentDataStoreMode;

  /**
   * Add one or more initializers to the builder.
   * To replace initializers, please refer to {@link #replaceInitializers(ComponentConfigurer[])}.
   * 
   * @param initializers the initializers to add
   * @return a reference to the builder
   */
  public DataSystemBuilder initializers(ComponentConfigurer<DataSource>... initializers) {
    for (ComponentConfigurer<DataSource> initializer : initializers) {
      this.initializers.add(initializer);
    }
    return this;
  }

  /**
   * Replaces any existing initializers with the given initializers.
   * To add initializers, please refer to {@link #initializers(ComponentConfigurer[])}.
   * 
   * @param initializers the initializers to replace the current initializers with
   * @return a reference to this builder
   */
  public DataSystemBuilder replaceInitializers(ComponentConfigurer<DataSource>... initializers) {
    this.initializers.clear();
    for (ComponentConfigurer<DataSource> initializer : initializers) {
      this.initializers.add(initializer);
    }
    return this;
  }

  /**
   * Add one or more synchronizers to the builder.
   * To replace synchronizers, please refer to {@link #replaceSynchronizers(ComponentConfigurer[])}.
   * 
   * @param synchronizers the synchronizers to add
   * @return a reference to the builder
   */
  public DataSystemBuilder synchronizers(ComponentConfigurer<DataSource>... synchronizers) {
    for (ComponentConfigurer<DataSource> synchronizer : synchronizers) {
      this.synchronizers.add(synchronizer);
    }
    return this;
  }

  /**
   * Replaces any existing synchronizers with the given synchronizers.
   * To add synchronizers, please refer to {@link #synchronizers(ComponentConfigurer[])}.
   * 
   * @param synchronizers the synchronizers to replace the current synchronizers with
   * @return a reference to this builder
   */
  public DataSystemBuilder replaceSynchronizers(ComponentConfigurer<DataSource>... synchronizers) {
    this.synchronizers.clear();
    for (ComponentConfigurer<DataSource> synchronizer : synchronizers) {
      this.synchronizers.add(synchronizer);
    }
    return this;
  }

  /**
   * Configure the FDv1 fallback synchronizer.
   * <p>
   * LaunchDarkly can instruct the SDK to fall back to this synchronizer.
   * </p>
   * 
   * @param fDv1FallbackSynchronizer the FDv1 fallback synchronizer
   * @return a reference to the builder
   */
  public DataSystemBuilder fDv1FallbackSynchronizer(ComponentConfigurer<DataSource> fDv1FallbackSynchronizer) {
    this.fDv1FallbackSynchronizer = fDv1FallbackSynchronizer;
    return this;
  }

  /**
   * Configures the persistent data store.
   * <p>
   * The SDK will use the persistent data store to store feature flag data.
   * </p>
   * 
   * @param persistentStore the persistent data store
   * @param mode the mode for the persistent data store
   * @return a reference to the builder
   * @see DataSystemConfiguration.DataStoreMode
   */
  public DataSystemBuilder persistentStore(ComponentConfigurer<DataStore> persistentStore, DataSystemConfiguration.DataStoreMode mode) {
    this.persistentStore = persistentStore;
    this.persistentDataStoreMode = mode;
    return this;
  }

  /**
   * Build the data system configuration.
   * <p>
   * This method is internal and should not be called by application code.
   * This function should remain internal.
   * </p>
   * 
   * @return the data system configuration
   */
  public DataSystemConfiguration build() {
    return new DataSystemConfiguration(
        ImmutableList.copyOf(initializers),
        ImmutableList.copyOf(synchronizers),
        fDv1FallbackSynchronizer,
        persistentStore,
        persistentDataStoreMode);
  }
}

