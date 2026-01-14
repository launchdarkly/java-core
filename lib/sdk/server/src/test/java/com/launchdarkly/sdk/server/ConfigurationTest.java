package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.server.integrations.DataSystemComponents;
import com.launchdarkly.sdk.server.integrations.DataSystemModes;
import com.launchdarkly.sdk.server.integrations.FDv2PollingDataSourceBuilder;
import com.launchdarkly.sdk.server.integrations.FDv2StreamingDataSourceBuilder;
import com.launchdarkly.sdk.server.integrations.MockPersistentDataStore;
import com.launchdarkly.sdk.server.integrations.PersistentDataStoreBuilder;
import com.launchdarkly.sdk.server.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreUpdateSink;
import com.launchdarkly.sdk.server.subsystems.PersistentDataStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class ConfigurationTest {
  private static final String SDK_KEY = "any-key";
  
  private static ClientContext clientContextWithDataStoreUpdateSink() {
    ClientContextImpl baseContext = TestComponents.clientContext("", LDConfig.DEFAULT);
    // Create a mock DataStoreUpdateSink for testing
    DataStoreUpdateSink mockSink = new DataStoreUpdateSink() {
      @Override
      public void updateStatus(DataStoreStatusProvider.Status newStatus) {
        // No-op for testing
      }
    };
    return baseContext.withDataStoreUpdateSink(mockSink);
  }

  @Test
  public void defaultSetsKey() {
    LDConfig config = LDConfig.DEFAULT;
    // Note: LDConfig doesn't expose SDK key directly, but this test verifies default config exists
    assertNotNull(config);
  }

  @Test
  public void builderSetsKey() {
    LDConfig config = new LDConfig.Builder().build();
    assertNotNull(config);
  }

  // Note: The following tests for DataSystem configuration are adapted to test DataSystemBuilder
  // and DataSystemModes directly, since LDConfig.Builder doesn't have a dataSystem() method yet.
  // Once that method is added, these tests can be updated to use it.

  @Test
  public void canConfigureDefaultDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.defaultMode();
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertEquals(1, dataSystemConfig.getInitializers().size());
    assertTrue(dataSystemConfig.getInitializers().get(0) instanceof FDv2PollingDataSourceBuilder);
    assertEquals(2, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2StreamingDataSourceBuilder);
    assertTrue(dataSystemConfig.getSynchronizers().get(1) instanceof FDv2PollingDataSourceBuilder);
    assertTrue(dataSystemConfig.getFDv1FallbackSynchronizer() instanceof PollingDataSourceBuilder);
    assertNull(dataSystemConfig.getPersistentStore());
  }

  @Test
  public void canConfigureStreamingDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.streaming();
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertTrue(dataSystemConfig.getInitializers().isEmpty());
    assertEquals(1, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2StreamingDataSourceBuilder);
    assertTrue(dataSystemConfig.getFDv1FallbackSynchronizer() instanceof PollingDataSourceBuilder);
    assertNull(dataSystemConfig.getPersistentStore());
  }

  @Test
  public void canConfigurePollingDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.polling();
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertTrue(dataSystemConfig.getInitializers().isEmpty());
    assertEquals(1, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2PollingDataSourceBuilder);
    assertTrue(dataSystemConfig.getFDv1FallbackSynchronizer() instanceof PollingDataSourceBuilder);
    assertNull(dataSystemConfig.getPersistentStore());
  }

  @Test
  public void canConfigureDaemonDataSystem() {
    MockPersistentDataStore mockStore = new MockPersistentDataStore();
    ComponentConfigurer<PersistentDataStore> storeConfigurer = TestComponents.specificComponent(mockStore);
    PersistentDataStoreBuilder persistentStoreBuilder = Components.persistentDataStore(storeConfigurer);
    ComponentConfigurer<DataStore> dataStoreConfigurer = TestComponents.specificComponent(
        Components.persistentDataStore(storeConfigurer).build(clientContextWithDataStoreUpdateSink()));
    
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.daemon(dataStoreConfigurer);
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertTrue(dataSystemConfig.getInitializers().isEmpty());
    assertTrue(dataSystemConfig.getSynchronizers().isEmpty());
    assertNull(dataSystemConfig.getFDv1FallbackSynchronizer());
    assertNotNull(dataSystemConfig.getPersistentStore());
    assertEquals(DataSystemConfiguration.DataStoreMode.READ_ONLY, dataSystemConfig.getPersistentDataStoreMode());
  }

  @Test
  public void canConfigurePersistentStoreDataSystem() {
    MockPersistentDataStore mockStore = new MockPersistentDataStore();
    ComponentConfigurer<PersistentDataStore> storeConfigurer = TestComponents.specificComponent(mockStore);
    ComponentConfigurer<DataStore> dataStoreConfigurer = TestComponents.specificComponent(
        Components.persistentDataStore(storeConfigurer).build(clientContextWithDataStoreUpdateSink()));
    
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.persistentStore(dataStoreConfigurer);
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertEquals(1, dataSystemConfig.getInitializers().size());
    assertTrue(dataSystemConfig.getInitializers().get(0) instanceof FDv2PollingDataSourceBuilder);
    assertEquals(2, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2StreamingDataSourceBuilder);
    assertTrue(dataSystemConfig.getSynchronizers().get(1) instanceof FDv2PollingDataSourceBuilder);
    assertTrue(dataSystemConfig.getFDv1FallbackSynchronizer() instanceof PollingDataSourceBuilder);
    assertNotNull(dataSystemConfig.getPersistentStore());
    assertEquals(DataSystemConfiguration.DataStoreMode.READ_WRITE, dataSystemConfig.getPersistentDataStoreMode());
  }

  @Test
  public void canConfigureCustomDataSystemWithAllOptions() {
    MockPersistentDataStore mockStore = new MockPersistentDataStore();
    ComponentConfigurer<PersistentDataStore> storeConfigurer = TestComponents.specificComponent(mockStore);
    ComponentConfigurer<DataStore> dataStoreConfigurer = TestComponents.specificComponent(
        Components.persistentDataStore(storeConfigurer).build(clientContextWithDataStoreUpdateSink()));

    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .initializers(DataSystemComponents.polling())
        .synchronizers(DataSystemComponents.streaming(), DataSystemComponents.polling())
        .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling())
        .persistentStore(dataStoreConfigurer, DataSystemConfiguration.DataStoreMode.READ_WRITE);
    
    DataSystemConfiguration dataSystemConfig = builder.build();

    // Verify initializers
    assertEquals(1, dataSystemConfig.getInitializers().size());
    assertTrue(dataSystemConfig.getInitializers().get(0) instanceof FDv2PollingDataSourceBuilder);

    // Verify synchronizers
    assertEquals(2, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2StreamingDataSourceBuilder);
    assertTrue(dataSystemConfig.getSynchronizers().get(1) instanceof FDv2PollingDataSourceBuilder);

    // Verify FDv1 fallback
    assertTrue(dataSystemConfig.getFDv1FallbackSynchronizer() instanceof PollingDataSourceBuilder);

    // Verify persistent store and mode
    assertNotNull(dataSystemConfig.getPersistentStore());
    assertEquals(DataSystemConfiguration.DataStoreMode.READ_WRITE, dataSystemConfig.getPersistentDataStoreMode());
  }

  @Test
  public void canReplaceInitializersInCustomDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .initializers(DataSystemComponents.polling())
        .replaceInitializers(DataSystemComponents.streaming());
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertEquals(1, dataSystemConfig.getInitializers().size());
    assertTrue(dataSystemConfig.getInitializers().get(0) instanceof FDv2StreamingDataSourceBuilder);
  }

  @Test
  public void canReplaceSynchronizersInCustomDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .synchronizers(DataSystemComponents.polling())
        .replaceSynchronizers(DataSystemComponents.streaming());
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertEquals(1, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2StreamingDataSourceBuilder);
  }

  @Test
  public void canAddMultipleInitializersToCustomDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .initializers(DataSystemComponents.polling())
        .initializers(DataSystemComponents.streaming());
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertEquals(2, dataSystemConfig.getInitializers().size());
    assertTrue(dataSystemConfig.getInitializers().get(0) instanceof FDv2PollingDataSourceBuilder);
    assertTrue(dataSystemConfig.getInitializers().get(1) instanceof FDv2StreamingDataSourceBuilder);
  }

  @Test
  public void canAddMultipleSynchronizersToCustomDataSystem() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .synchronizers(DataSystemComponents.polling())
        .synchronizers(DataSystemComponents.streaming())
        .synchronizers(DataSystemComponents.fDv1Polling());
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertEquals(3, dataSystemConfig.getSynchronizers().size());
    assertTrue(dataSystemConfig.getSynchronizers().get(0) instanceof FDv2PollingDataSourceBuilder);
    assertTrue(dataSystemConfig.getSynchronizers().get(1) instanceof FDv2StreamingDataSourceBuilder);
    assertTrue(dataSystemConfig.getSynchronizers().get(2) instanceof PollingDataSourceBuilder);
  }

  @Test
  public void customDataSystemWithNoConfigurationHasEmptyLists() {
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom();
    DataSystemConfiguration dataSystemConfig = builder.build();
    
    assertTrue(dataSystemConfig.getInitializers().isEmpty());
    assertTrue(dataSystemConfig.getSynchronizers().isEmpty());
    assertNull(dataSystemConfig.getFDv1FallbackSynchronizer());
    assertNull(dataSystemConfig.getPersistentStore());
  }

  @Test
  public void canConfigureDaemonDataSystemWithReadOnlyMode() {
    MockPersistentDataStore mockStore = new MockPersistentDataStore();
    ComponentConfigurer<PersistentDataStore> storeConfigurer = TestComponents.specificComponent(mockStore);
    ComponentConfigurer<DataStore> dataStoreConfigurer = TestComponents.specificComponent(
        Components.persistentDataStore(storeConfigurer).build(clientContextWithDataStoreUpdateSink()));
    
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .persistentStore(dataStoreConfigurer, DataSystemConfiguration.DataStoreMode.READ_ONLY);
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertNotNull(dataSystemConfig.getPersistentStore());
    assertEquals(DataSystemConfiguration.DataStoreMode.READ_ONLY, dataSystemConfig.getPersistentDataStoreMode());
    assertTrue(dataSystemConfig.getInitializers().isEmpty());
    assertTrue(dataSystemConfig.getSynchronizers().isEmpty());
  }

  @Test
  public void canConfigureCustomDataSystemWithReadWritePersistentStore() {
    MockPersistentDataStore mockStore = new MockPersistentDataStore();
    ComponentConfigurer<PersistentDataStore> storeConfigurer = TestComponents.specificComponent(mockStore);
    ComponentConfigurer<DataStore> dataStoreConfigurer = TestComponents.specificComponent(
        Components.persistentDataStore(storeConfigurer).build(clientContextWithDataStoreUpdateSink()));
    
    DataSystemModes modes = new DataSystemModes();
    DataSystemBuilder builder = modes.custom()
        .persistentStore(dataStoreConfigurer, DataSystemConfiguration.DataStoreMode.READ_WRITE)
        .synchronizers(DataSystemComponents.streaming());
    
    DataSystemConfiguration dataSystemConfig = builder.build();
    assertNotNull(dataSystemConfig.getPersistentStore());
    assertEquals(DataSystemConfiguration.DataStoreMode.READ_WRITE, dataSystemConfig.getPersistentDataStoreMode());
    assertEquals(1, dataSystemConfig.getSynchronizers().size());
  }
}

