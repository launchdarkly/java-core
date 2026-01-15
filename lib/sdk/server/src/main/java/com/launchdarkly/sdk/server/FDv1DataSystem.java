package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.LoggingConfiguration;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Future;

/**
 * Internal implementation of the FDv1 data system.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class FDv1DataSystem implements DataSystem, Closeable {
  private final DataSource dataSource;
  private final DataStore dataStore;
  private final ReadOnlyStore store;
  private final FlagChangeNotifier flagChanged;
  private final DataSourceStatusProvider dataSourceStatusProvider;
  private final DataStoreStatusProvider dataStoreStatusProvider;
  private boolean disposed = false;

  /**
   * Testing access to internal components.
   */
  static final class TestingAccess {
    final DataSource dataSource;

    TestingAccess(DataSource dataSource) {
      this.dataSource = dataSource;
    }
  }

  final TestingAccess testing;

  /**
   * Gets the underlying data store. This is needed for the evaluator.
   * @return the underlying data store
   */
  DataStore getUnderlyingStore() {
    return dataStore;
  }

  private FDv1DataSystem(
      DataStore store,
      DataStoreStatusProvider dataStoreStatusProvider,
      DataSourceStatusProvider dataSourceStatusProvider,
      DataSource dataSource,
      FlagChangeNotifier flagChanged
  ) {
    this.dataStoreStatusProvider = dataStoreStatusProvider;
    this.dataSourceStatusProvider = dataSourceStatusProvider;
    this.store = new ReadonlyStoreFacade(store);
    this.flagChanged = flagChanged;
    this.dataSource = dataSource;
    this.dataStore = store;
    this.testing = new TestingAccess(dataSource);
  }

  /**
   * Creates a new FDv1DataSystem instance.
   *
   * @param logger the logger
   * @param config the SDK configuration
   * @param clientContext the client context
   * @param logConfig the logging configuration
   * @return a new FDv1DataSystem instance
   */
  static FDv1DataSystem create(
      LDLogger logger,
      LDConfig config,
      ClientContextImpl clientContext,
      LoggingConfiguration logConfig
  ) {
    DataStoreUpdatesImpl dataStoreUpdates = new DataStoreUpdatesImpl(
        EventBroadcasterImpl.forDataStoreStatus(clientContext.sharedExecutor, logger));

    DataStore dataStore = (config.dataStore == null ? Components.inMemoryDataStore() : config.dataStore)
        .build(clientContext.withDataStoreUpdateSink(dataStoreUpdates));

    DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(dataStore, dataStoreUpdates);

    // Create a single flag change broadcaster to be shared between DataSourceUpdatesImpl and FlagTrackerImpl
    EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster =
        EventBroadcasterImpl.forFlagChangeEvents(clientContext.sharedExecutor, logger);

    // Create a single data source status broadcaster to be shared between DataSourceUpdatesImpl and DataSourceStatusProviderImpl
    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> dataSourceStatusBroadcaster =
        EventBroadcasterImpl.forDataSourceStatus(clientContext.sharedExecutor, logger);

    DataSourceUpdatesImpl dataSourceUpdates = new DataSourceUpdatesImpl(
        dataStore,
        dataStoreStatusProvider,
        flagChangeBroadcaster,
        dataSourceStatusBroadcaster,
        clientContext.sharedExecutor,
        logConfig.getLogDataSourceOutageAsErrorAfter(),
        logger
    );

    ComponentConfigurer<DataSource> dataSourceFactory = config.offline
        ? Components.externalUpdatesOnly()
        : (config.dataSource == null ? Components.streamingDataSource() : config.dataSource);
    DataSource dataSource = dataSourceFactory.build(clientContext.withDataSourceUpdateSink(dataSourceUpdates));
    DataSourceStatusProvider dataSourceStatusProvider = new DataSourceStatusProviderImpl(
        dataSourceStatusBroadcaster,
        dataSourceUpdates);

    FlagChangeNotifier flagChanged = new FlagChangedFacade(dataSourceUpdates);

    return new FDv1DataSystem(
        dataStore,
        dataStoreStatusProvider,
        dataSourceStatusProvider,
        dataSource,
        flagChanged
    );
  }

  @Override
  public ReadOnlyStore getStore() {
    return store;
  }

  @Override
  public Future<Void> start() {
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
  public void close() throws IOException {
    if (disposed) {
      return;
    }
    try {
      if (dataSource instanceof Closeable) {
        ((Closeable) dataSource).close();
      }
      if (dataStore instanceof Closeable) {
        ((Closeable) dataStore).close();
      }
      // DataSourceUpdatesImpl doesn't implement Closeable
    } finally {
      disposed = true;
    }
  }
}

