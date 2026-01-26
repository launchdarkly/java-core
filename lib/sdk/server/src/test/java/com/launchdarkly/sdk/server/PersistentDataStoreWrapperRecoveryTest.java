package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.integrations.MockPersistentDataStore;
import com.launchdarkly.sdk.server.integrations.PersistentDataStoreBuilder;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.DataStoreTestTypes.TEST_ITEMS;
import static com.launchdarkly.sdk.server.TestComponents.sharedExecutor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the external data source recovery behavior in PersistentDataStoreWrapper.
 * These tests verify that when a persistent store recovers from an outage, it syncs
 * data from an external data source (like InMemoryDataStore) rather than its internal cache.
 */
@SuppressWarnings("javadoc")
public class PersistentDataStoreWrapperRecoveryTest extends BaseTest {
  private static final Duration TIMEOUT_FOR_RECOVERY = Duration.ofSeconds(2);
  private static final RuntimeException FAKE_ERROR = new RuntimeException("test error");

  private final MockPersistentDataStore core;
  private final DataStoreUpdatesImpl dataStoreUpdates;

  public PersistentDataStoreWrapperRecoveryTest() {
    this.core = new MockPersistentDataStore();
    EventBroadcasterImpl<DataStoreStatusProvider.StatusListener, DataStoreStatusProvider.Status> statusBroadcaster =
        EventBroadcasterImpl.forDataStoreStatus(sharedExecutor, testLogger);
    this.dataStoreUpdates = new DataStoreUpdatesImpl(statusBroadcaster);
  }

  @After
  public void tearDown() throws IOException {
    // Cleanup if needed
  }

  // Helper method to create FullDataSet with deleted items
  private FullDataSet<ItemDescriptor> createDataSetWithDeletedItem(DataKind kind, String key, int version) {
    Map<DataKind, Map<String, ItemDescriptor>> dataMap = new HashMap<>();
    dataMap.put(kind, new HashMap<>());
    dataMap.get(kind).put(key, ItemDescriptor.deletedItem(version));
    ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> builder = ImmutableList.builder();
    for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : dataMap.entrySet()) {
      ImmutableList.Builder<Map.Entry<String, ItemDescriptor>> itemsBuilder = ImmutableList.builder();
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().entrySet()) {
        itemsBuilder.add(new AbstractMap.SimpleEntry<>(item.getKey(), item.getValue()));
      }
      builder.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(itemsBuilder.build())));
    }
    return new FullDataSet<>(builder.build());
  }

  // Helper method to merge two FullDataSets
  private FullDataSet<ItemDescriptor> mergeDataSets(FullDataSet<ItemDescriptor> set1, FullDataSet<ItemDescriptor> set2) {
    Map<DataKind, Map<String, ItemDescriptor>> merged = new HashMap<>();
    
    // Add all from set1
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> e : set1.getData()) {
      merged.put(e.getKey(), new HashMap<>());
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().getItems()) {
        merged.get(e.getKey()).put(item.getKey(), item.getValue());
      }
    }
    
    // Add/overwrite with set2
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> e : set2.getData()) {
      merged.computeIfAbsent(e.getKey(), k -> new HashMap<>());
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().getItems()) {
        merged.get(e.getKey()).put(item.getKey(), item.getValue());
      }
    }
    
    ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> builder = ImmutableList.builder();
    for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : merged.entrySet()) {
      ImmutableList.Builder<Map.Entry<String, ItemDescriptor>> itemsBuilder = ImmutableList.builder();
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().entrySet()) {
        itemsBuilder.add(new AbstractMap.SimpleEntry<>(item.getKey(), item.getValue()));
      }
      builder.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(itemsBuilder.build())));
    }
    return new FullDataSet<>(builder.build());
  }

  private PersistentDataStoreWrapper makeWrapperWithExternalSource(CacheExporter externalSource) {
    PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(
        core,
        Duration.ofMillis(-1), // Infinite TTL
        PersistentDataStoreBuilder.StaleValuesPolicy.EVICT,
        false,
        dataStoreUpdates::updateStatus,
        sharedExecutor,
        testLogger
    );
    if (externalSource != null) {
      wrapper.setCacheExporter(externalSource);
    }
    return wrapper;
  }

  @Test
  public void externalDataSourceSyncWhenStoreRecoversSyncsFromExternalSource() throws Exception {
    // Create a mock external data source with some initial data
    MockCacheExporter externalSource = new MockCacheExporter();
    DataStoreTestTypes.TestItem item1 = new DataStoreTestTypes.TestItem("key1", "item1", 1);
    DataStoreTestTypes.TestItem item2 = new DataStoreTestTypes.TestItem("key2", "item2", 1);

    externalSource.setData(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1)
        .add(TEST_ITEMS, item2)
        .build());

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      // Initialize the wrapper with some initial data
      wrapper.init(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, item1)
          .build());

      // Cause a store error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, item1));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status status1 = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", status1);
      assertFalse(status1.isAvailable());

      // While the store is down, update the external data source with new data
      DataStoreTestTypes.TestItem item3 = new DataStoreTestTypes.TestItem("key3", "item3", 1);
      externalSource.setData(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "item1", 2))
          .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key2", "item2", 2))
          .add(TEST_ITEMS, item3)
          .build());

      // Make store available again
      core.fakeError = null;
      core.unavailable = false;

      // Wait for recovery
      DataStoreStatusProvider.Status status2 = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected recovery status update", status2);
      assertTrue(status2.isAvailable());
      assertFalse(status2.isRefreshNeeded()); // Should not need refresh in infinite cache mode

      // Verify that ALL data from external source was synced to persistent store
      SerializedItemDescriptor syncedItem1 = core.data.get(TEST_ITEMS).get("key1");
      assertNotNull(syncedItem1);
      assertEquals(2, syncedItem1.getVersion());

      SerializedItemDescriptor syncedItem2 = core.data.get(TEST_ITEMS).get("key2");
      assertNotNull(syncedItem2);
      assertEquals(2, syncedItem2.getVersion());

      SerializedItemDescriptor syncedItem3 = core.data.get(TEST_ITEMS).get("key3");
      assertNotNull(syncedItem3);
      assertEquals(1, syncedItem3.getVersion());

      // Check log message
      assertTrue(logCapture.getMessages().stream()
          .anyMatch(m -> m.getLevel().name().equals("WARN") &&
              m.getText().contains("Successfully updated persistent store from external data source")));
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWithMultipleKindsSyncsAllKinds() throws Exception {
    MockCacheExporter externalSource = new MockCacheExporter();
    DataStoreTestTypes.TestItem item1 = new DataStoreTestTypes.TestItem("key1", "item1", 1);
    DataStoreTestTypes.TestItem item2 = new DataStoreTestTypes.TestItem("key2", "item2", 1);

    externalSource.setData(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1)
        .add(DataStoreTestTypes.OTHER_TEST_ITEMS, item2)
        .build());

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      wrapper.init(externalSource.exportAll());

      // Cause error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, item1));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status unavailableStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected unavailable status", unavailableStatus);

      // Update both kinds in external source
      externalSource.setData(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "item1", 3))
          .add(DataStoreTestTypes.OTHER_TEST_ITEMS, new DataStoreTestTypes.TestItem("key2", "item2", 3))
          .build());

      // Recover
      core.fakeError = null;
      core.unavailable = false;

      DataStoreStatusProvider.Status recoveryStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected recovery status", recoveryStatus);

      // Both kinds should be synced
      SerializedItemDescriptor syncedItem1 = core.data.get(TEST_ITEMS).get("key1");
      assertNotNull(syncedItem1);
      assertEquals(3, syncedItem1.getVersion());

      SerializedItemDescriptor syncedItem2 = core.data.get(DataStoreTestTypes.OTHER_TEST_ITEMS).get("key2");
      assertNotNull(syncedItem2);
      assertEquals(3, syncedItem2.getVersion());
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWhenExportFailsDoesNotRecover() throws Exception {
    MockCacheExporter externalSource = new MockCacheExporter();
    externalSource.setData(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "item1", 1))
        .build());

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      wrapper.init(externalSource.exportAll());

      // Cause error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, new DataStoreTestTypes.TestItem("key1", "item1", 2)));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status unavailableStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected unavailable status", unavailableStatus);

      // Make external source throw an error during export
      RuntimeException exportError = new RuntimeException("export failed");
      externalSource.exportError = exportError;

      // Make store available, but external source will fail
      core.fakeError = null;
      core.unavailable = false;

      // Wait a bit to ensure polling happens (but not full recovery timeout)
      Thread.sleep(600);

      // Should NOT have recovered because export failed
      // Try to get a status update with a short timeout - should fail
      try {
        DataStoreStatusProvider.Status status = statuses.poll(100, TimeUnit.MILLISECONDS);
        if (status != null && status.isAvailable()) {
          fail("Should not have received a recovery status update");
        }
      } catch (Exception e) {
        // Expected - no status update received
      }

      // Check log message
      assertTrue(logCapture.getMessages().stream()
          .anyMatch(m -> m.getLevel().name().equals("ERROR") &&
              m.getText().contains("Failed to export data from external source")));
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWhenInitCoreFailsDoesNotRecover() throws Exception {
    MockCacheExporter externalSource = new MockCacheExporter();
    externalSource.setData(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "item1", 1))
        .build());

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      wrapper.init(externalSource.exportAll());

      // Cause error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, new DataStoreTestTypes.TestItem("key1", "item1", 2)));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status unavailableStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected unavailable status", unavailableStatus);

      // Make store available but init will fail
      core.unavailable = false;
      core.fakeError = FAKE_ERROR; // Still throws error on operations

      // Wait a bit for polling
      Thread.sleep(600);

      // Should NOT have recovered because init failed
      // Try to get a status update with a short timeout - should fail
      try {
        DataStoreStatusProvider.Status status = statuses.poll(100, TimeUnit.MILLISECONDS);
        if (status != null && status.isAvailable()) {
          fail("Should not have received a recovery status update");
        }
      } catch (Exception e) {
        // Expected - no status update received
      }

      // Check log message
      assertTrue(logCapture.getMessages().stream()
          .anyMatch(m -> m.getLevel().name().equals("ERROR") &&
              m.getText().contains("Tried to write external data to persistent store after outage, but failed")));
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void backwardCompatibilityWithoutExternalSourceUsesCacheSync() throws Exception {
    // This test verifies that when no external source is provided, the wrapper
    // falls back to the original cache-based recovery behavior
    PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(
        core,
        Duration.ofMillis(-1), // Infinite TTL
        PersistentDataStoreBuilder.StaleValuesPolicy.EVICT,
        false,
        dataStoreUpdates::updateStatus,
        sharedExecutor,
        testLogger
    );
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      DataStoreTestTypes.TestItem item1 = new DataStoreTestTypes.TestItem("key1", "item1", 1);
      wrapper.init(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, item1)
          .build());

      // Cause error and update cache
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, item1));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status unavailableStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected unavailable status", unavailableStatus);

      // The cache should have the update even though store failed
      assertEquals(new ItemDescriptor(2, item1), wrapper.get(TEST_ITEMS, "key1"));

      // Recover
      core.fakeError = null;
      core.unavailable = false;

      DataStoreStatusProvider.Status status = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", status);

      // Should have synced from cache (original behavior)
      SerializedItemDescriptor syncedItem = core.data.get(TEST_ITEMS).get("key1");
      assertNotNull(syncedItem);
      assertEquals(2, syncedItem.getVersion());

      // Check log message
      assertTrue(logCapture.getMessages().stream()
          .anyMatch(m -> m.getLevel().name().equals("WARN") &&
              m.getText().contains("Successfully updated persistent store from cached data")));
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWithEmptyExternalSourceHandlesGracefully() throws Exception {
    MockCacheExporter externalSource = new MockCacheExporter();
    // External source has no data
    externalSource.setData(new DataStoreTestTypes.DataBuilder().build());

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      wrapper.init(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "item1", 1))
          .build());

      // Cause error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, new DataStoreTestTypes.TestItem("key1", "item1", 2)));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status status = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", status);

      // Recover with empty external source
      core.fakeError = null;
      core.unavailable = false;

      DataStoreStatusProvider.Status recoveryStatus3 = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", recoveryStatus3);

      // Should have cleared the persistent store (synced empty data)
      assertFalse(core.data.containsKey(TEST_ITEMS) && core.data.get(TEST_ITEMS).containsKey("key1"));
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWithDeletedItemsSyncsCorrectly() throws Exception {
    MockCacheExporter externalSource = new MockCacheExporter();
    DataStoreTestTypes.TestItem item1 = new DataStoreTestTypes.TestItem("key1", "item1", 1);

    DataStoreTestTypes.DataBuilder builder = new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1);
    // Manually add deleted item
    Map<DataKind, Map<String, ItemDescriptor>> dataMap = new java.util.HashMap<>();
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> e : builder.build().getData()) {
      dataMap.put(e.getKey(), new java.util.HashMap<>());
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().getItems()) {
        dataMap.get(e.getKey()).put(item.getKey(), item.getValue());
      }
    }
    dataMap.computeIfAbsent(TEST_ITEMS, k -> new java.util.HashMap<>())
        .put("key2", ItemDescriptor.deletedItem(1));
    ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> builder2 = ImmutableList.builder();
    for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : dataMap.entrySet()) {
      ImmutableList.Builder<Map.Entry<String, ItemDescriptor>> itemsBuilder = ImmutableList.builder();
      for (Map.Entry<String, ItemDescriptor> item : e.getValue().entrySet()) {
        itemsBuilder.add(new AbstractMap.SimpleEntry<>(item.getKey(), item.getValue()));
      }
      builder2.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(itemsBuilder.build())));
    }
    externalSource.setData(new FullDataSet<>(builder2.build()));

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      wrapper.init(externalSource.exportAll());

      // Cause error
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, item1));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status status = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", status);

      // Update external source with deleted item at higher version
      FullDataSet<ItemDescriptor> regularData2 = new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, item1)
          .build();
      FullDataSet<ItemDescriptor> deletedData2 = createDataSetWithDeletedItem(TEST_ITEMS, "key2", 2);
      externalSource.setData(mergeDataSets(regularData2, deletedData2));

      // Recover
      core.fakeError = null;
      core.unavailable = false;

      DataStoreStatusProvider.Status recoveryStatus4 = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", recoveryStatus4);

      // Verify deleted item was synced correctly
      assertTrue(core.data.get(TEST_ITEMS).containsKey("key2"));
      SerializedItemDescriptor deletedItem = core.data.get(TEST_ITEMS).get("key2");
      assertTrue(deletedItem.isDeleted());
      assertEquals(2, deletedItem.getVersion());
    } finally {
      wrapper.close();
    }
  }

  @Test
  public void externalDataSourceSyncWhenExternalStoreNotInitializedFallsBackToCache() throws Exception {
    // Create an uninitialized external store
    MockCacheExporter externalSource = new MockCacheExporter();
    externalSource.isInitialized = false; // Not initialized

    PersistentDataStoreWrapper wrapper = makeWrapperWithExternalSource(externalSource);
    try {
      DataStoreStatusProvider dataStoreStatusProvider = new DataStoreStatusProviderImpl(wrapper, dataStoreUpdates);
      BlockingQueue<DataStoreStatusProvider.Status> statuses = new LinkedBlockingQueue<>();
      dataStoreStatusProvider.addStatusListener(statuses::add);

      DataStoreTestTypes.TestItem item1 = new DataStoreTestTypes.TestItem("key1", "item1", 1);
      wrapper.init(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, item1)
          .build());

      // Cause error and update cache
      core.unavailable = true;
      core.fakeError = FAKE_ERROR;
      try {
        wrapper.upsert(TEST_ITEMS, "key1", new ItemDescriptor(2, item1));
        fail("Expected exception");
      } catch (RuntimeException e) {
        assertEquals(FAKE_ERROR.getMessage(), e.getMessage());
      }

      DataStoreStatusProvider.Status unavailableStatus = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected unavailable status", unavailableStatus);

      // The cache should have the update even though store failed
      assertEquals(new ItemDescriptor(2, item1), wrapper.get(TEST_ITEMS, "key1"));

      // Update external source with different data, but keep it uninitialized
      externalSource.setData(new DataStoreTestTypes.DataBuilder()
          .add(TEST_ITEMS, new DataStoreTestTypes.TestItem("key1", "wrong-item", 99))
          .build());

      // Recover
      core.fakeError = null;
      core.unavailable = false;

      DataStoreStatusProvider.Status status = statuses.poll(TIMEOUT_FOR_RECOVERY.toMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Expected status update", status);

      // Should have synced from CACHE (not external source) because external store is not initialized
      SerializedItemDescriptor syncedItem = core.data.get(TEST_ITEMS).get("key1");
      assertNotNull(syncedItem);
      assertEquals(2, syncedItem.getVersion());

      // Check log message
      assertTrue(logCapture.getMessages().stream()
          .anyMatch(m -> m.getLevel().name().equals("WARN") &&
              m.getText().contains("Successfully updated persistent store from cached data")));
    } finally {
      wrapper.close();
    }
  }

  /**
   * Mock implementation of CacheExporter for testing.
   */
  private static class MockCacheExporter implements CacheExporter {
    private FullDataSet<ItemDescriptor> data = new DataStoreTestTypes.DataBuilder().build();
    public RuntimeException exportError;
    public boolean isInitialized = true;

    public void setData(FullDataSet<ItemDescriptor> data) {
      this.data = data;
    }

    @Override
    public FullDataSet<ItemDescriptor> exportAll() {
      if (exportError != null) {
        throw exportError;
      }
      return data;
    }

    @Override
    public boolean isInitialized() {
      return isInitialized;
    }
  }
}
