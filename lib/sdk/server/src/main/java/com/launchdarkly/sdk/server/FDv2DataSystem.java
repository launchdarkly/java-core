package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.subsystems.*;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.Future;

import static com.launchdarkly.sdk.server.ComponentsImpl.toHttpProperties;

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
  private boolean disposed = false;

  private FDv2DataSystem(
    DataStore store,
    DataSource dataSource,
    DataSourceStatusProvider dataSourceStatusProvider,
    DataStoreStatusProvider dataStoreStatusProvider,
    FlagChangeNotifier flagChanged
  ) {
    this.store = store;
    this.dataSource = dataSource;
    this.dataStoreStatusProvider = dataStoreStatusProvider;
    this.dataSourceStatusProvider = dataSourceStatusProvider;
    this.flagChanged = flagChanged;
    this.readOnlyStore = new ReadonlyStoreFacade(store);
  }

  private static class SynchronizerFactoryWrapper implements FDv2DataSource.SynchronizerFactory {

    private final SynchronizerBuilder builder;
    private final DataSourceBuilderContext context;

    public SynchronizerFactoryWrapper(SynchronizerBuilder builder, DataSourceBuilderContext context) {
      this.builder = builder;
      this.context = context;
    }

    @Override
    public Synchronizer build() {
      return builder.build(context);
    }
  }

  private static class InitializerFactoryWrapper implements FDv2DataSource.InitializerFactory {

    private final InitializerBuilder builder;
    private final DataSourceBuilderContext context;

    public InitializerFactoryWrapper(InitializerBuilder builder, DataSourceBuilderContext context) {
      this.builder = builder;
      this.context = context;
    }

    @Override
    public Initializer build() {
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

    InMemoryDataStore store = new InMemoryDataStore();

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

    DataSystemConfiguration dataSystemConfiguration = config.dataSystem.build();
    SelectorSource selectorSource = new SelectorSourceFacade(store);

    DataSourceBuilderContext builderContext = new DataSourceBuilderContext(
      clientContext.getBaseLogger(),
      clientContext.getThreadPriority(),
      dataSourceUpdates,
      clientContext.getServiceEndpoints(),
      clientContext.getHttp(),
      clientContext.sharedExecutor,
      clientContext.diagnosticStore,
      selectorSource
    );

    ImmutableList<FDv2DataSource.InitializerFactory> initializerFactories = dataSystemConfiguration.getInitializers().stream()
      .map(initializer -> new InitializerFactoryWrapper(initializer, builderContext))
      .collect(ImmutableList.toImmutableList());

    ImmutableList<FDv2DataSource.SynchronizerFactory> synchronizerFactories = dataSystemConfiguration.getSynchronizers().stream()
      .map(synchronizer -> new SynchronizerFactoryWrapper(synchronizer, builderContext))
      .collect(ImmutableList.toImmutableList());

    DataSource dataSource = new FDv2DataSource(
      initializerFactories,
      synchronizerFactories,
      dataSourceUpdates
    );
    DataSourceStatusProvider dataSourceStatusProvider = new DataSourceStatusProviderImpl(
      dataSourceStatusBroadcaster,
      dataSourceUpdates);

    FlagChangeNotifier flagChanged = new FlagChangedFacade(dataSourceUpdates);

    return new FDv2DataSystem(
      store,
      dataSource,
      dataSourceStatusProvider,
      dataStoreStatusProvider,
      flagChanged
    );
  }

  @Override
  public ReadOnlyStore getStore() {
    return readOnlyStore;
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
      if (store instanceof Closeable) {
        ((Closeable) store).close();
      }
    } finally {
      disposed = true;
    }
  }
}

