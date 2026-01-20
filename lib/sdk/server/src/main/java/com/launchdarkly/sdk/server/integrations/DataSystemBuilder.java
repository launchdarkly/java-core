package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.subsystems.*;

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

  private final List<DataSourceBuilder<Initializer>> initializers = new ArrayList<>();
  private final List<DataSourceBuilder<Synchronizer>> synchronizers = new ArrayList<>();
  private ComponentConfigurer<DataSource> fDv1FallbackSynchronizer;
  private ComponentConfigurer<DataStore> persistentStore;
  private DataSystemConfiguration.DataStoreMode persistentDataStoreMode;

  /**
   * Add one or more initializers to the builder.
   * To replace initializers, please refer to {@link #replaceInitializers(DataSourceBuilder[])}.
   *
   * @param initializers the initializers to add
   * @return a reference to the builder
   */
  @SafeVarargs
  public final DataSystemBuilder initializers(DataSourceBuilder<Initializer>... initializers) {
    for (DataSourceBuilder<Initializer> initializer : initializers) {
      this.initializers.add(initializer);
    }
    return this;
  }

  /**
   * Replaces any existing initializers with the given initializers.
   * To add initializers, please refer to {@link #initializers(InitializerBuilder[])}.
   *
   * @param initializers the initializers to replace the current initializers with
   * @return a reference to this builder
   */
  @SafeVarargs
  public final DataSystemBuilder replaceInitializers(DataSourceBuilder<Initializer>... initializers) {
    this.initializers.clear();
    for (DataSourceBuilder<Initializer> initializer : initializers) {
      this.initializers.add(initializer);
    }
    return this;
  }

  /**
   * Add one or more synchronizers to the builder.
   * To replace synchronizers, please refer to {@link #replaceSynchronizers(SynchronizerBuilder[])}.
   *
   * @param synchronizers the synchronizers to add
   * @return a reference to the builder
   */
  @SafeVarargs
  public final DataSystemBuilder synchronizers(DataSourceBuilder<Synchronizer>... synchronizers) {
    for (DataSourceBuilder<Synchronizer> synchronizer : synchronizers) {
      this.synchronizers.add(synchronizer);
    }
    return this;
  }

  /**
   * Replaces any existing synchronizers with the given synchronizers.
   * To add synchronizers, please refer to {@link #synchronizers(SynchronizerBuilder[])}.
   *
   * @param synchronizers the synchronizers to replace the current synchronizers with
   * @return a reference to this builder
   */
  @SafeVarargs
  public final DataSystemBuilder replaceSynchronizers(DataSourceBuilder<Synchronizer>... synchronizers) {
    this.synchronizers.clear();
    for (DataSourceBuilder<Synchronizer> synchronizer : synchronizers) {
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
  @SuppressWarnings("unchecked")
  public DataSystemBuilder fDv1FallbackSynchronizer(ComponentConfigurer<DataSource> fDv1FallbackSynchronizer) {
    // Legacy DataSource configurers are used for FDv1 backward compatibility
    // This is safe because DataSource is only used in the fallback context
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

