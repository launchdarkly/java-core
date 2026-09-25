package com.launchdarkly.sdk.server.subsystems;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;

/**
 * Configuration for the SDK's data acquisition and storage strategy.
 * <p>
 * Applications should use {@link com.launchdarkly.sdk.server.integrations.DataSystemBuilder} or
 * {@link com.launchdarkly.sdk.server.integrations.DataSystemModes} to create instances of this class,
 * rather than calling the constructor directly.
 * </p>
 */
public final class DataSystemConfiguration {

  /**
   * The persistent data store mode.
   * <p>
   * This enum can be extended without a major version. Code should provide this value in configuration,
   * but it should not use the enum itself, for example, in a switch-case.
   * </p>
   */
  public enum DataStoreMode {
    /**
     * The data system will only read from the persistent store.
     */
    READ_ONLY,

    /**
     * The data system can read from, and write to, the persistent store.
     */
    READ_WRITE
  }

  private final ImmutableList<DataSourceBuilder<Initializer>> initializers;
  private final ImmutableList<DataSourceBuilder<Synchronizer>> synchronizers;
  private final ComponentConfigurer<DataSource> fDv1FallbackSynchronizer;
  private final ComponentConfigurer<DataStore> persistentStore;
  private final DataStoreMode persistentDataStoreMode;
  private final ComponentConfigurer<OverrideSource> overrideSource;

  /**
   * Creates an instance without an override source.
   * <p>
   * This constructor is internal and should not be called by application code.
   * </p>
   *
   * @param initializers see {@link #getInitializers()}
   * @param synchronizers see {@link #getSynchronizers()}
   * @param fDv1FallbackSynchronizer see {@link #getFDv1FallbackSynchronizer()}
   * @param persistentStore see {@link #getPersistentStore()}
   * @param persistentDataStoreMode see {@link #getPersistentDataStoreMode()}
   */
  public DataSystemConfiguration(
      ImmutableList<DataSourceBuilder<Initializer>> initializers,
      ImmutableList<DataSourceBuilder<Synchronizer>> synchronizers,
      ComponentConfigurer<DataSource> fDv1FallbackSynchronizer,
      ComponentConfigurer<DataStore> persistentStore,
      DataStoreMode persistentDataStoreMode) {
    this(initializers, synchronizers, fDv1FallbackSynchronizer, persistentStore, persistentDataStoreMode, null);
  }

  /**
   * Creates an instance.
   * <p>
   * This constructor is internal and should not be called by application code.
   * </p>
   *
   * @param initializers see {@link #getInitializers()}
   * @param synchronizers see {@link #getSynchronizers()}
   * @param fDv1FallbackSynchronizer see {@link #getFDv1FallbackSynchronizer()}
   * @param persistentStore see {@link #getPersistentStore()}
   * @param persistentDataStoreMode see {@link #getPersistentDataStoreMode()}
   * @param overrideSource see {@link #getOverrideSource()}
   * @since 7.18.0
   */
  public DataSystemConfiguration(
      ImmutableList<DataSourceBuilder<Initializer>> initializers,
      ImmutableList<DataSourceBuilder<Synchronizer>> synchronizers,
      ComponentConfigurer<DataSource> fDv1FallbackSynchronizer,
      ComponentConfigurer<DataStore> persistentStore,
      DataStoreMode persistentDataStoreMode,
      ComponentConfigurer<OverrideSource> overrideSource) {
    this.initializers = initializers;
    this.synchronizers = synchronizers;
    this.fDv1FallbackSynchronizer = fDv1FallbackSynchronizer;
    this.persistentStore = persistentStore;
    this.persistentDataStoreMode = persistentDataStoreMode;
    this.overrideSource = overrideSource;
  }

  /**
   * A list of factories for creating initializers for initialization.
   *
   * @return the list of initializer configurers
   */
  public ImmutableList<DataSourceBuilder<Initializer>> getInitializers() {
    return initializers;
  }

  /**
   * A list of factories for creating synchronizers for synchronization.
   *
   * @return the list of synchronizer configurers
   */
  public ImmutableList<DataSourceBuilder<Synchronizer>> getSynchronizers() {
    return synchronizers;
  }

  /**
   * A synchronizer to fall back to when FDv1 fallback has been requested.
   *
   * @return the FDv1 fallback synchronizer configurer, or null
   */
  public ComponentConfigurer<DataSource> getFDv1FallbackSynchronizer() {
    return fDv1FallbackSynchronizer;
  }

  /**
   * An optional factory for creating a persistent data store. This is optional, and if no persistent store is configured, it will be
   * null.
   * <p>
   * The persistent store itself will implement {@link PersistentDataStore}, but we expect that to be wrapped by a factory which can
   * operate at the {@link DataStore} level.
   * </p>
   * 
   * @return the persistent store configurer, or null
   */
  public ComponentConfigurer<DataStore> getPersistentStore() {
    return persistentStore;
  }

  /**
   * The mode of operation for the persistent data store.
   *
   * @return the persistent data store mode
   */
  public DataStoreMode getPersistentDataStoreMode() {
    return persistentDataStoreMode;
  }

  /**
   * An optional factory for creating an override source. The source supplies flag and segment
   * overrides that take precedence over LaunchDarkly data at evaluation time. It is not part of
   * the initializer or synchronizer pipeline and does not affect the client's initialization
   * status. Flag overrides are currently experimental and subject to change.
   *
   * @return the override source configurer, or null
   * @since 7.18.0
   */
  public ComponentConfigurer<OverrideSource> getOverrideSource() {
    return overrideSource;
  }
}

