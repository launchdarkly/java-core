package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider.CacheStats;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration.DataStoreMode;
import com.launchdarkly.sdk.server.subsystems.TransactionalDataStore;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.launchdarkly.sdk.server.DataStoreTestTypes.TEST_ITEMS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.TestItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class WriteThroughStoreTest {
  private final TestItem item1 = new TestItem("key1", "item1", 10);
  private final TestItem item2 = new TestItem("key2", "item2", 11);
  
  private WriteThroughStore store;
  
  @After
  public void teardown() throws Exception {
    if (store != null) {
      store.close();
    }
  }

  private FullDataSet<ItemDescriptor> createTestDataSet() {
    return new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1, item2)
        .build();
  }

  private ChangeSet<ItemDescriptor> createFullChangeSet() {
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key1", new ItemDescriptor(10, item1)),
            new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(11, item2))
        ))
    );

    return new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        changeSetData.entrySet(),
        null,
        true
    );
  }

  // Construction Tests

  @Test
  public void constructorWithPersistenceSetsActiveReadStoreToPersistent() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertEquals(10, result.getVersion());
    assertTrue(persistentStore.wasGetCalled);
  }

  @Test
  public void constructorWithoutPersistenceSetsActiveReadStoreToMemory() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);
    
    memoryStore.upsert(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertEquals(10, result.getVersion());
  }

  // Init Tests

  @Test
  public void initWithoutPersistenceInitializesMemoryStoreOnly() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);
    
    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    assertTrue(memoryStore.isInitialized());
    ItemDescriptor result = memoryStore.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertEquals(10, result.getVersion());
  }

  @Test
  public void initWithPersistenceReadWriteInitializesBothStores() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    assertTrue(memoryStore.isInitialized());
    assertTrue(persistentStore.isInitialized());
    assertTrue(persistentStore.wasInitCalled);
  }

  @Test
  public void initWithPersistenceReadOnlyInitializesMemoryStoreOnly() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_ONLY);
    
    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    assertTrue(memoryStore.isInitialized());
    assertFalse(persistentStore.wasInitCalled);
  }

  @Test
  public void initWithShouldPersistFalseDoesNotCallPersistentStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    // Create test data with shouldPersist=false (e.g., from file data source)
    FullDataSet<ItemDescriptor> testData = new FullDataSet<>(
        createTestDataSet().getData(),
        false // shouldPersist=false
    );
    store.init(testData);

    assertTrue(memoryStore.isInitialized());
    // Persistent store should NOT be called when shouldPersist=false
    assertFalse(persistentStore.wasInitCalled);
    assertFalse(persistentStore.isInitialized());
  }

  @Test
  public void initSwitchesActiveReadStoreToMemory() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(5, new TestItem("key1", "old", 5)));

    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertEquals(10, result.getVersion());
    assertEquals(item1, result.getItem());
  }

  // Get/GetAll Tests

  @Test
  public void getBeforeSwitchReadsFromPersistentStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertTrue(persistentStore.wasGetCalled);
  }

  @Test
  public void getAfterSwitchReadsFromMemoryStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    persistentStore.resetCallTracking();

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertFalse(persistentStore.wasGetCalled);
  }

  @Test
  public void getAllAfterSwitchReadsFromMemoryStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    FullDataSet<ItemDescriptor> testData = createTestDataSet();
    store.init(testData);

    persistentStore.resetCallTracking();

    KeyedItems<ItemDescriptor> result = store.getAll(TEST_ITEMS);
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, ItemDescriptor> ignored : result.getItems()) {
      count++;
    }
    assertEquals(2, count);
    assertFalse(persistentStore.wasGetAllCalled);
  }

  // Upsert Tests

  @Test
  public void upsertWithoutPersistenceUpdatesMemoryStoreOnly() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);
    
    boolean result = store.upsert(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));
    assertTrue(result);

    ItemDescriptor retrieved = memoryStore.get(TEST_ITEMS, "key1");
    assertNotNull(retrieved);
    assertEquals(10, retrieved.getVersion());
  }

  @Test
  public void upsertWithPersistenceReadWriteUpdatesBothStores() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    boolean result = store.upsert(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));
    assertTrue(result);
    assertTrue(persistentStore.wasUpsertCalled);

    ItemDescriptor retrieved = memoryStore.get(TEST_ITEMS, "key1");
    assertNotNull(retrieved);
  }

  @Test
  public void upsertWithPersistenceReadOnlyUpdatesMemoryStoreOnly() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_ONLY);
    
    boolean result = store.upsert(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));
    assertTrue(result);
    assertFalse(persistentStore.wasUpsertCalled);
  }

  @Test
  public void upsertWhenPersistentStoreFailsReturnsFalse() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.failUpsert = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    boolean result = store.upsert(TEST_ITEMS, "key1", new ItemDescriptor(10, item1));
    assertFalse(result);
  }

  // Apply Tests

  @Test
  public void applyWithFullChangeSetAppliesToBothStores() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();
    store.apply(changeSet);

    assertTrue(memoryStore.isInitialized());
    assertTrue(persistentStore.wasApplyCalled);
  }

  @Test
  public void applyWithPartialChangeSetAppliesToBothStores() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    persistentStore.resetCallTracking();
    store.apply(changeSet);

    assertTrue(persistentStore.wasApplyCalled);
    ItemDescriptor result = memoryStore.get(TEST_ITEMS, "key3");
    assertNotNull(result);
    assertEquals(item3, result.getItem());
  }

  @Test
  public void applyWithLegacyPersistentStoreFullChangeSetCallsInit() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();
    store.apply(changeSet);

    assertTrue(persistentStore.wasInitCalled);
  }

  @Test
  public void applyWithLegacyPersistentStorePartialChangeSetCallsUpsert() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    persistentStore.resetCallTracking();
    store.apply(changeSet);

    assertTrue(persistentStore.wasUpsertCalled);
  }

  @Test
  public void applyWithShouldPersistFalseDoesNotCallPersistentStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );
    // Create change set with shouldPersist=false (e.g., from file data source)
    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        false // shouldPersist=false
    );

    persistentStore.resetCallTracking();
    store.apply(changeSet);

    // Memory store should be updated
    ItemDescriptor result = memoryStore.get(TEST_ITEMS, "key3");
    assertNotNull(result);
    assertEquals(item3, result.getItem());
    
    // Persistent store should NOT be called when shouldPersist=false
    assertFalse(persistentStore.wasUpsertCalled);
    assertFalse(persistentStore.wasInitCalled);
  }

  @Test
  public void applyWithFullChangeSetShouldPersistFalseDoesNotCallPersistentStore() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    // Create full change set with shouldPersist=false
    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        createFullChangeSet().getData(),
        null,
        false // shouldPersist=false
    );
    
    persistentStore.resetCallTracking();
    store.apply(changeSet);

    // Memory store should be initialized
    assertTrue(memoryStore.isInitialized());
    
    // Persistent store should NOT be called when shouldPersist=false
    assertFalse(persistentStore.wasApplyCalled);
  }

  @Test
  public void applySwitchesActiveReadStoreToMemory() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(5, new TestItem("key1", "old", 5)));

    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();
    store.apply(changeSet);

    ItemDescriptor result = store.get(TEST_ITEMS, "key1");
    assertNotNull(result);
    assertEquals(10, result.getVersion());
    assertEquals(item1, result.getItem());

    persistentStore.resetCallTracking();
    store.get(TEST_ITEMS, "key1");
    assertFalse(persistentStore.wasGetCalled);
  }

  // Initialized Tests

  @Test
  public void initializedWithPersistenceReturnsPersistentStoreStatus() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    assertFalse(store.isInitialized());

    persistentStore.setInitialized(true);

    assertTrue(store.isInitialized());
  }

  @Test
  public void initializedWithoutPersistenceReturnsMemoryStoreStatus() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);
    
    assertFalse(store.isInitialized());

    memoryStore.init(createTestDataSet());

    assertTrue(store.isInitialized());
  }

  // Store Switching Tests

  @Test
  public void storeSwitchingHappensOnlyOnce() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(5, new TestItem("key1", "old", 5)));

    store.init(createTestDataSet());

    ItemDescriptor result1 = store.get(TEST_ITEMS, "key1");
    assertNotNull(result1);
    assertEquals(10, result1.getVersion());

    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(20, new TestItem("key1", "newer", 20)));

    FullDataSet<ItemDescriptor> newData = new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, new TestItem("key1", "item1-v15", 15))
        .build();
    store.init(newData);

    ItemDescriptor result2 = store.get(TEST_ITEMS, "key1");
    assertNotNull(result2);
    assertEquals(15, result2.getVersion());
    assertFalse(result2.getVersion() == 20);
  }

  // Selector Tests

  @Test
  public void selectorReturnsMemoryStoreSelector() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(42, "test-state"),
        ImmutableList.<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>of(),
        null,
        true
    );

    store.apply(changeSet);

    Selector selector = store.getSelector();
    assertEquals(42, selector.getVersion());
    assertEquals("test-state", selector.getState());
  }

  // StatusMonitoringEnabled Tests

  @Test
  public void statusMonitoringEnabledWithPersistenceReturnsPersistentStoreValue() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.statusMonitoringEnabledValue = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    assertTrue(store.isStatusMonitoringEnabled());
  }

  @Test
  public void statusMonitoringEnabledWithoutPersistenceReturnsFalse() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);
    
    assertFalse(store.isStatusMonitoringEnabled());
  }

  // Dispose Tests

  @Test
  public void disposeDisposesBothStores() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);

    store.close();

    assertTrue(persistentStore.wasDisposeCalled);
  }

  @Test
  public void disposeWithoutPersistenceDisposesMemoryStoreOnly() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();

    store = new WriteThroughStore(memoryStore, null, DataStoreMode.READ_WRITE);

    store.close();
    // No exception means it worked
  }

  // Error Handling Tests

  @Test
  public void applyWithLegacyStorePartialChangeSetThrowsWhenUpsertFails() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.failUpsert = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertEquals("Failure to apply data set to persistent store.", e.getMessage());
    }
  }

  @Test
  public void applyWithLegacyStorePartialChangeSetThrowsWhenOneOfMultipleUpsertsFails() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    persistentStore.setUpsertFailureForKey("key4");

    TestItem item3 = new TestItem("key3", "item3", 30);
    TestItem item4 = new TestItem("key4", "item4", 40);
    TestItem item5 = new TestItem("key5", "item5", 50);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3)),
            new AbstractMap.SimpleEntry<>("key4", new ItemDescriptor(40, item4)),
            new AbstractMap.SimpleEntry<>("key5", new ItemDescriptor(50, item5))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertEquals("Failure to apply data set to persistent store.", e.getMessage());
    }
  }

  @Test
  public void applyWithLegacyStoreFullChangeSetPropagatesInitException() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.throwOnInit = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();

    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertEquals("Init failed", e.getMessage());
    }
  }

  @Test
  public void applyWithTransactionalStorePropagatesApplyException() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();
    persistentStore.throwOnApply = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();

    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertEquals("Apply failed", e.getMessage());
    }
  }

  @Test
  public void applyWithLegacyStorePartialChangeSetMemoryStoreStillUpdatedBeforeException() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.failUpsert = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      // Expected
    }

    ItemDescriptor result = memoryStore.get(TEST_ITEMS, "key3");
    assertNotNull(result);
    assertEquals(item3, result.getItem());
  }

  @Test
  public void applyWithLegacyStoreNoneChangeSetDoesNotThrow() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.failUpsert = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    store.init(createTestDataSet());

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.None,
        Selector.make(2, "state2"),
        ImmutableList.<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>of(),
        null,
        true
    );

    store.apply(changeSet);
    // No exception means it worked
  }

  @Test
  public void applySwitchesToMemoryStoreEvenWhenPersistentStoreApplyFails() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockPersistentStore persistentStore = new MockPersistentStore();
    persistentStore.failUpsert = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    // Set up persistent store with old data
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(5, new TestItem("key1", "old", 5)));

    // Before apply, reads should come from persistent store
    ItemDescriptor resultBefore = store.get(TEST_ITEMS, "key1");
    assertNotNull(resultBefore);
    assertEquals(5, resultBefore.getVersion());
    assertEquals(new TestItem("key1", "old", 5), resultBefore.getItem());

    TestItem item3 = new TestItem("key3", "item3", 30);
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
        ))
    );

    ChangeSet<ItemDescriptor> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true
    );

    // Apply should throw due to persistent store failure
    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      // Expected
    }

    // After apply, even though it failed for persistence, we should have switched to memory store
    // Memory store should have the new data
    ItemDescriptor resultAfter = store.get(TEST_ITEMS, "key3");
    assertNotNull(resultAfter);
    assertEquals(item3, resultAfter.getItem());

    // Verify we're reading from memory, not persistent store
    persistentStore.resetCallTracking();
    store.get(TEST_ITEMS, "key3");
    assertFalse(persistentStore.wasGetCalled);
  }

  @Test
  public void applySwitchesToMemoryStoreEvenWhenTransactionalStoreApplyFails() throws Exception {
    InMemoryDataStore memoryStore = new InMemoryDataStore();
    MockTransactionalPersistentStore persistentStore = new MockTransactionalPersistentStore();
    persistentStore.throwOnApply = true;

    store = new WriteThroughStore(memoryStore, persistentStore, DataStoreMode.READ_WRITE);
    
    // Set up persistent store with old data
    persistentStore.setData(TEST_ITEMS, "key1", new ItemDescriptor(5, new TestItem("key1", "old", 5)));

    // Before apply, reads should come from persistent store
    ItemDescriptor resultBefore = store.get(TEST_ITEMS, "key1");
    assertNotNull(resultBefore);
    assertEquals(5, resultBefore.getVersion());

    ChangeSet<ItemDescriptor> changeSet = createFullChangeSet();

    // Apply should throw due to persistent store failure
    try {
      store.apply(changeSet);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertEquals("Apply failed", e.getMessage());
    }

    // After apply, even though it failed for persistence, we should have switched to memory store
    // Memory store should have the new data
    ItemDescriptor resultAfter = store.get(TEST_ITEMS, "key1");
    assertNotNull(resultAfter);
    assertEquals(10, resultAfter.getVersion());
    assertEquals(item1, resultAfter.getItem());

    // Verify we're reading from memory, not persistent store
    persistentStore.resetCallTracking();
    store.get(TEST_ITEMS, "key1");
    assertFalse(persistentStore.wasGetCalled);
  }

  // Mock Stores

  private static class MockPersistentStore implements DataStore {
    private final Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    private final Set<String> keysToFailOn = new HashSet<>();
    private boolean initialized;

    public boolean wasInitCalled;
    public boolean wasGetCalled;
    public boolean wasGetAllCalled;
    public boolean wasUpsertCalled;
    public boolean wasDisposeCalled;
    public boolean failUpsert;
    public boolean throwOnInit;
    public boolean statusMonitoringEnabledValue;

    public void setUpsertFailureForKey(String key) {
      keysToFailOn.add(key);
    }

    public void resetCallTracking() {
      wasInitCalled = false;
      wasGetCalled = false;
      wasGetAllCalled = false;
      wasUpsertCalled = false;
      wasDisposeCalled = false;
    }

    public void setData(DataKind kind, String key, ItemDescriptor item) {
      data.computeIfAbsent(kind, k -> new HashMap<>()).put(key, item);
    }

    public void setInitialized(boolean value) {
      initialized = value;
    }

    @Override
    public void init(FullDataSet<ItemDescriptor> allData) {
      wasInitCalled = true;
      if (throwOnInit) {
        throw new RuntimeException("Init failed");
      }

      data.clear();
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindData : allData.getData()) {
        Map<String, ItemDescriptor> itemsMap = new HashMap<>();
        data.put(kindData.getKey(), itemsMap);
        for (Map.Entry<String, ItemDescriptor> item : kindData.getValue().getItems()) {
          itemsMap.put(item.getKey(), item.getValue());
        }
      }

      initialized = true;
    }

    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      wasGetCalled = true;
      Map<String, ItemDescriptor> kindData = data.get(kind);
      if (kindData != null) {
        return kindData.get(key);
      }
      return null;
    }

    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      wasGetAllCalled = true;
      Map<String, ItemDescriptor> kindData = data.get(kind);
      if (kindData != null) {
        return new KeyedItems<>(kindData.entrySet());
      }
      return new KeyedItems<>(ImmutableList.of());
    }

    @Override
    public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
      wasUpsertCalled = true;
      if (failUpsert || keysToFailOn.contains(key)) {
        return false;
      }

      Map<String, ItemDescriptor> itemsMap = data.computeIfAbsent(kind, k -> new HashMap<>());

      ItemDescriptor existing = itemsMap.get(key);
      if (existing != null) {
        if (item.getVersion() <= existing.getVersion()) {
          return false;
        }
      }

      itemsMap.put(key, item);
      return true;
    }

    @Override
    public boolean isInitialized() {
      return initialized;
    }

    @Override
    public boolean isStatusMonitoringEnabled() {
      return statusMonitoringEnabledValue;
    }

    @Override
    public CacheStats getCacheStats() {
      return null;
    }

    @Override
    public void close() throws IOException {
      wasDisposeCalled = true;
    }
  }

  private static class MockTransactionalPersistentStore extends MockPersistentStore implements TransactionalDataStore {
    public boolean wasApplyCalled;
    public boolean throwOnApply;

    @Override
    public void resetCallTracking() {
      super.resetCallTracking();
      wasApplyCalled = false;
    }

    @Override
    public void apply(ChangeSet<ItemDescriptor> changeSet) {
      wasApplyCalled = true;
      if (throwOnApply) {
        throw new RuntimeException("Apply failed");
      }

      switch (changeSet.getType()) {
        case Full:
          init(new FullDataSet<>(changeSet.getData(), changeSet.shouldPersist()));
          break;
        case Partial:
          for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindData : changeSet.getData()) {
            for (Map.Entry<String, ItemDescriptor> item : kindData.getValue().getItems()) {
              upsert(kindData.getKey(), item.getKey(), item.getValue());
            }
          }
          break;
        case None:
          break;
      }
    }

    @Override
    public Selector getSelector() {
      return Selector.EMPTY;
    }
  }
}
