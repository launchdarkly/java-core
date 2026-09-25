package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.subsystems.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration builder for the SDK's data acquisition and storage strategy.
 */
public final class DataSystemBuilder {

  private final List<DataSourceBuilder<Initializer>> initializers = new ArrayList<>();
  private final List<DataSourceBuilder<Synchronizer>> synchronizers = new ArrayList<>();
  private ComponentConfigurer<DataSource> fDv1FallbackSynchronizer;
  private ComponentConfigurer<DataStore> persistentStore;
  private DataSystemConfiguration.DataStoreMode persistentDataStoreMode;
  private ComponentConfigurer<OverrideSource> overrideSource;

  /**
   * Add one or more initializers to the builder.
   * To replace initializers, please refer to {@link #replaceInitializers(DataSourceBuilder[])}.
   *
   * @param initializers the initializers to add
   * @return a reference to the builder
   */
  @SafeVarargs
  public final DataSystemBuilder initializers(DataSourceBuilder<Initializer>... initializers) {
    this.initializers.addAll(Arrays.asList(initializers));
    return this;
  }

  /**
   * Replaces any existing initializers with the given initializers.
   * To add initializers, please refer to {@link #initializers(DataSourceBuilder[])}.
   *
   * @param initializers the initializers to replace the current initializers with
   * @return a reference to this builder
   */
  @SafeVarargs
  public final DataSystemBuilder replaceInitializers(DataSourceBuilder<Initializer>... initializers) {
    this.initializers.clear();
    this.initializers.addAll(Arrays.asList(initializers));
    return this;
  }

  /**
   * Add one or more synchronizers to the builder.
   * To replace synchronizers, please refer to {@link #replaceSynchronizers(DataSourceBuilder[])}.
   *
   * @param synchronizers the synchronizers to add
   * @return a reference to the builder
   */
  @SafeVarargs
  public final DataSystemBuilder synchronizers(DataSourceBuilder<Synchronizer>... synchronizers) {
    this.synchronizers.addAll(Arrays.asList(synchronizers));
    return this;
  }

  /**
   * Replaces any existing synchronizers with the given synchronizers.
   * To add synchronizers, please refer to {@link #synchronizers(DataSourceBuilder[])}.
   *
   * @param synchronizers the synchronizers to replace the current synchronizers with
   * @return a reference to this builder
   */
  @SafeVarargs
  public final DataSystemBuilder replaceSynchronizers(DataSourceBuilder<Synchronizer>... synchronizers) {
    this.synchronizers.clear();
    this.synchronizers.addAll(Arrays.asList(synchronizers));
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
   * Configures an override source. Flag overrides are currently experimental and subject to change.
   * <p>
   * The source supplies flag and segment definitions that take precedence over data received from
   * LaunchDarkly on a per-key basis. Overrides let an operator force one or more flags to a known
   * state on a running client, whether or not the client can reach LaunchDarkly. Flags not present
   * in the override data are unaffected.
   * </p>
   * <p>
   * The override source is not a data source. It has no effect on the client's initialization
   * status or data source status. Configuring it changes nothing until the source supplies an
   * override. At most one override source can be configured; a later call replaces the earlier one.
   * </p>
   * <p>
   * <b>Example:</b>
   * </p>
   * <pre><code>
   *     LDConfig config = new LDConfig.Builder()
   *       .dataSystem(Components.dataSystem().defaultMode()
   *         .overrides(FileOverrides.source().filePaths("/etc/launchdarkly/overrides.json")))
   *       .build();
   * </code></pre>
   *
   * @param overrideSource the override source configuration, or null for none
   * @return a reference to the builder
   * @since 7.18.0
   */
  public DataSystemBuilder overrides(ComponentConfigurer<OverrideSource> overrideSource) {
    this.overrideSource = overrideSource;
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
        persistentDataStoreMode,
        overrideSource);
  }
}

