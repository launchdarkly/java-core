package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.LoggingConfiguration;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Future;

/**
 * Internal implementation of the FDv2 data system.
 * <p>
 * This class is package-private and should not be used by application code.
 * <p>
 * This is a placeholder implementation. Not all dependencies are yet implemented.
 */
final class FDv2DataSystem implements DataSystem, Closeable {
  private final DataSource dataSource;
  private final DataStore store;
  private final ReadOnlyStore readOnlyStore;
  private final FlagChangeNotifier flagChanged;
  private final DataSourceStatusProvider dataSourceStatusProvider;
  private final DataStoreStatusProvider dataStoreStatusProvider;
  private final LDLogger logger;
  // The following are non-null only when an override source is configured. The layer holds the
  // override entries. The overlay serves them in preference to the store's data at the store read
  // boundary. The source populates the layer at runtime. None of them take part in
  // initialization or data source status.
  private final OverrideLayer overrideLayer;
  private final OverrideSinkImpl overrideSink;
  // Null after the source failed to start or was closed.
  private volatile OverrideSource overrideSource;
  private boolean disposed = false;

  private FDv2DataSystem(
    DataStore store,
    DataSource dataSource,
    DataSourceStatusProvider dataSourceStatusProvider,
    DataStoreStatusProvider dataStoreStatusProvider,
    FlagChangeNotifier flagChanged,
    EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster,
    OverrideSource overrideSource,
    LDLogger logger
  ) {
    this.store = store;
    this.dataSource = dataSource;
    this.dataStoreStatusProvider = dataStoreStatusProvider;
    this.dataSourceStatusProvider = dataSourceStatusProvider;
    this.flagChanged = flagChanged;
    this.logger = logger;
    ReadOnlyStore baseStore = new ReadonlyStoreFacade(store);
    this.overrideSource = overrideSource;
    if (overrideSource == null) {
      this.overrideLayer = null;
      this.overrideSink = null;
      this.readOnlyStore = baseStore;
    } else {
      this.overrideLayer = new OverrideLayer();
      this.overrideSink = new OverrideSinkImpl(overrideLayer, baseStore, flagChangeBroadcaster,
          logger.subLogger(Loggers.DATA_SOURCE_LOGGER_NAME));
      this.readOnlyStore = new OverrideOverlayStore(baseStore, overrideLayer);
    }
  }

  private static class FactoryWrapper<TDataSource> implements FDv2DataSource.DataSourceFactory<TDataSource> {

    private final DataSourceBuilder<TDataSource> builder;
    private final DataSourceBuildInputs context;

    public FactoryWrapper(DataSourceBuilder<TDataSource> builder, DataSourceBuildInputs context) {
      this.builder = builder;
      this.context = context;
    }

    @Override
    public TDataSource build() {
      return builder.build(context);
    }
  }

  /**
   * Creates a new FDv2DataSystem instance.
   * <p>
   * This is a placeholder implementation. Not all dependencies are yet implemented.
   *
   * @param logger the logger
   * @param config the SDK configuration
   * @param clientContext the client context
   * @param logConfig the logging configuration
   * @return a new FDv2DataSystem instance
   * @throws UnsupportedOperationException since this is not yet fully implemented
   */
  static FDv2DataSystem create(
    LDLogger logger,
    LDConfig config,
    ClientContextImpl clientContext,
    LoggingConfiguration logConfig
  ) {
    if (config.dataSystem == null) {
      throw new IllegalArgumentException("DataSystem configuration is required for FDv2DataSystem");
    }
    DataStoreUpdatesImpl dataStoreUpdates = new DataStoreUpdatesImpl(
      EventBroadcasterImpl.forDataStoreStatus(clientContext.sharedExecutor, logger));

    DataSystemConfiguration dataSystemConfiguration = config.dataSystem.build();
    
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    
    DataStore persistentStore = null;
    if (dataSystemConfiguration.getPersistentStore() != null) {
      persistentStore = dataSystemConfiguration.getPersistentStore().build(clientContext.withDataStoreUpdateSink(dataStoreUpdates));
      
      // Configure persistent store to sync from memory store during recovery (ReadWrite mode only)
      if (persistentStore != null && dataSystemConfiguration.getPersistentDataStoreMode() == DataSystemConfiguration.DataStoreMode.READ_WRITE) {
        if (persistentStore instanceof SettableCache) {
          ((SettableCache) persistentStore).setCacheExporter(memoryStore);
        }
      }
    }
    
    WriteThroughStore store = new WriteThroughStore(
        memoryStore,
        persistentStore,
        dataSystemConfiguration.getPersistentDataStoreMode());

    DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(store, dataStoreUpdates);

    // Create a single flag change broadcaster to be shared between DataSourceUpdatesImpl and FlagTrackerImpl
    EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster =
      EventBroadcasterImpl.forFlagChangeEvents(clientContext.sharedExecutor, logger);

    // Create a single data source status broadcaster to be shared between DataSourceUpdatesImpl and DataSourceStatusProviderImpl
    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> dataSourceStatusBroadcaster =
      EventBroadcasterImpl.forDataSourceStatus(clientContext.sharedExecutor, logger);

    DataSourceUpdatesImpl dataSourceUpdates = new DataSourceUpdatesImpl(
      store,
      dataStoreStatusProvider,
      flagChangeBroadcaster,
      dataSourceStatusBroadcaster,
      clientContext.sharedExecutor,
      logConfig.getLogDataSourceOutageAsErrorAfter(),
      logger
    );

    SelectorSource selectorSource = new SelectorSourceFacade(store);

    DataSourceBuildInputs builderContext = new DataSourceBuildInputs(
      clientContext.getBaseLogger(),
      clientContext.getThreadPriority(),
      dataSourceUpdates,
      clientContext.getServiceEndpoints(),
      clientContext.getHttp(),
      clientContext.sharedExecutor,
      clientContext.diagnosticStore,
      selectorSource
    );

    ImmutableList<FDv2DataSource.DataSourceFactory<Initializer>> initializerFactories = dataSystemConfiguration.getInitializers().stream()
      .map(initializer -> new FactoryWrapper<>(initializer, builderContext))
      .collect(ImmutableList.toImmutableList());

    ImmutableList<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizerFactories = dataSystemConfiguration.getSynchronizers().stream()
      .map(synchronizer -> new FactoryWrapper<>(synchronizer, builderContext))
      .collect(ImmutableList.toImmutableList());

    // Create FDv1 fallback synchronizer factory if configured
    FDv2DataSource.DataSourceFactory<Synchronizer> fdv1FallbackFactory = null;
    if (dataSystemConfiguration.getFDv1FallbackSynchronizer() != null) {
      fdv1FallbackFactory = () -> {
        // Wrap the FDv1 DataSource as a Synchronizer using the adapter
        return new DataSourceSynchronizerAdapter(
          updateSink -> dataSystemConfiguration
              .getFDv1FallbackSynchronizer()
              .build(clientContext.withDataSourceUpdateSink(updateSink))
        );
      };
    }

    final DataSource dataSource;
    if (config.offline) {
      dataSource = Components.externalUpdatesOnly().build(clientContext.withDataSourceUpdateSink(dataSourceUpdates));
    } else {
      dataSource = new FDv2DataSource(
        initializerFactories,
        synchronizerFactories,
        fdv1FallbackFactory,
        dataSourceUpdates,
        config.threadPriority,
        clientContext.getBaseLogger().subLogger(Loggers.DATA_SOURCE_LOGGER_NAME),
        clientContext.sharedExecutor
      );
    }

    DataSourceStatusProvider dataSourceStatusProvider = new DataSourceStatusProviderImpl(
      dataSourceStatusBroadcaster,
      dataSourceUpdates);

    FlagChangeNotifier flagChanged = new FlagChangedFacade(dataSourceUpdates);

    // The override source is built like any other component. Invalid configuration fails here, the
    // same way an invalid data source or data store configuration fails. An offline client starts
    // no data sources and no override source.
    OverrideSource overrideSource = null;
    if (dataSystemConfiguration.getOverrideSource() != null) {
      if (config.offline) {
        logger.warn("Offline mode is enabled; the override source is not started and no overrides are served");
      } else {
        overrideSource = dataSystemConfiguration.getOverrideSource().build(clientContext);
      }
    }

    FDv2DataSystem system = new FDv2DataSystem(
      store,
      dataSource,
      dataSourceStatusProvider,
      dataStoreStatusProvider,
      flagChanged,
      flagChangeBroadcaster,
      overrideSource,
      logger
    );
    if (system.overrideLayer != null) {
      // A LaunchDarkly change must also notify the flags whose overrides depend on it.
      dataSourceUpdates.setExternalDependencies(system.overrideLayer);
    }
    return system;
  }

  @Override
  public ReadOnlyStore getStore() {
    return readOnlyStore;
  }

  @Override
  public Future<Void> start() {
    if (overrideSource != null) {
      // The source starts before the data source, so a source that loads synchronously has its
      // overrides in place before the client begins evaluating. Its initial load is part of
      // starting the client. A source that fails to start does not prevent the client from
      // operating: the failure is logged, the source is closed, and the client runs without
      // overrides. The SDK's own file-based source does not throw from start; it logs a failed
      // load and retries it. A custom source could throw, and an application in an incident is
      // better served by a client that runs without its overrides than by one that does not start.
      try {
        overrideSource.start(overrideSink);
      } catch (RuntimeException e) {
        logger.error("Unable to start the override source; the client runs without overrides: {}",
            LogValues.exceptionSummary(e));
        logger.debug(LogValues.exceptionTrace(e));
        closeOverrideSource("Unable to close the override source after it failed to start: {}");
        // The source may have supplied a snapshot before it failed. Clearing the layer removes it,
        // so the client runs without overrides as logged, and a late snapshot from the closed
        // source is ignored.
        overrideSink.closeAndClear();
      }
    }
    return dataSource.start();
  }

  @Override
  public boolean isInitialized() {
    return dataSource.isInitialized();
  }

  @Override
  public FlagChangeNotifier getFlagChanged() {
    return flagChanged;
  }

  @Override
  public DataSourceStatusProvider getDataSourceStatusProvider() {
    return dataSourceStatusProvider;
  }

  @Override
  public DataStoreStatusProvider getDataStoreStatusProvider() {
    return dataStoreStatusProvider;
  }

  @Override
  public String getEnvironmentId() {
    return store.getEnvironmentId();
  }

  // A custom source can throw from close. That is logged and not thrown, so the rest of the client
  // still shuts down, or still starts after a failed start.
  private void closeOverrideSource(String failureMessage) {
    OverrideSource source = overrideSource;
    overrideSource = null;
    try {
      source.close();
    } catch (IOException | RuntimeException e) {
      logger.warn(failureMessage, LogValues.exceptionSummary(e));
      logger.debug(LogValues.exceptionTrace(e));
    }
  }

  @Override
  public OverrideLayer getOverrideLayer() {
    return overrideLayer;
  }

  @Override
  public void close() throws IOException {
    if (disposed) {
      return;
    }
    try {
      if (overrideSource != null) {
        // The sink stops accepting snapshots first, so a snapshot that the source supplies while it
        // shuts down cannot update the layer or notify listeners through components that are being
        // closed.
        overrideSink.close();
        closeOverrideSource("Unable to close the override source: {}");
      }
      dataSource.close();
      store.close();
    } finally {
      disposed = true;
    }
  }
}

