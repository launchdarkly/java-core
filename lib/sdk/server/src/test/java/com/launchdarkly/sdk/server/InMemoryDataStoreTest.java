package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.Map;

import static com.launchdarkly.sdk.server.DataStoreTestTypes.OTHER_TEST_ITEMS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.TEST_ITEMS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.TestItem;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.toItemsMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class InMemoryDataStoreTest extends DataStoreTestBase {

  private static final TestItem item1 = new TestItem("key1", "item1", 10);
  private static final TestItem item2 = new TestItem("key2", "item2", 11);
  private static final String item1Key = "key1";
  private static final int item1Version = 10;
  private static final String item2Key = "key2";
  private static final int item2Version = 11;

  @Override
  protected DataStore makeStore() {
    return new InMemoryDataStore();
  }

  private InMemoryDataStore typedStore() {
    return (InMemoryDataStore)store;
  }

  private void initStore() {
    com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet<ItemDescriptor> allData =
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1, item2)
            .build();
    store.init(allData);
  }

  @Test
  public void cacheStatsAreNull() {
    assertNull(makeStore().getCacheStats());
  }

  // Apply method tests

  @Test
  public void applyWithFullChangeSetReplacesAllData() {
    // Initialize store with some data
    initStore();

    // Create a full changeset with different data
    TestItem item3 = new TestItem("key3", "item3", 20);
    String item3Key = "key3";
    int item3Version = 20;

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind, 
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>(item3Key, new ItemDescriptor(item3Version, item3))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        changeSetData,
        "test-env",
        true
    );

    typedStore().apply(changeSet);

    // Old items should be gone
    assertNull(store.get(TEST_ITEMS, item1Key));
    assertNull(store.get(TEST_ITEMS, item2Key));

    // New item should exist
    ItemDescriptor result = store.get(TEST_ITEMS, item3Key);
    assertNotNull(result);
    assertEquals(item3Version, result.getVersion());
    assertEquals(item3, result.getItem());
  }

  @Test
  public void applyWithFullChangeSetSetsSelector() {
    Selector selector = Selector.make(42, "test-state");
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        selector,
        ImmutableList.of(),
        null,
        true
    );

    typedStore().apply(changeSet);

    assertEquals(selector.getVersion(), typedStore().getSelector().getVersion());
    assertEquals(selector.getState(), typedStore().getSelector().getState());
  }

  @Test
  public void applyWithFullChangeSetMarksStoreAsInitialized() {
    assertFalse(store.isInitialized());

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        ImmutableList.of(),
        null,
        true
    );

    typedStore().apply(changeSet);

    assertTrue(store.isInitialized());
  }

  @Test
  public void applyWithPartialChangeSetAddsNewItems() {
    initStore();

    TestItem item3 = new TestItem("key3", "item3", 20);
    String item3Key = "key3";
    int item3Version = 20;

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>(item3Key, new ItemDescriptor(item3Version, item3))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Old items should still exist
    assertNotNull(store.get(TEST_ITEMS, item1Key));
    assertNotNull(store.get(TEST_ITEMS, item2Key));

    // New item should exist
    ItemDescriptor result = store.get(TEST_ITEMS, item3Key);
    assertNotNull(result);
    assertEquals(item3Version, result.getVersion());
    assertEquals(item3, result.getItem());
  }

  @Test
  public void applyWithPartialChangeSetCanReplaceItems() {
    initStore();

    // Partial updates replace the entire data kind with the provided items
    TestItem item1Updated = new TestItem(item1Key, "item1-updated", item1Version + 10);
    int item1NewVersion = item1Version + 10;

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>(item1Key, new ItemDescriptor(item1NewVersion, item1Updated)),
                        new AbstractMap.SimpleEntry<>(item2Key, new ItemDescriptor(item2Version, item2)) // Must include all items in kind
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Item should be updated
    ItemDescriptor result = store.get(TEST_ITEMS, item1Key);
    assertNotNull(result);
    assertEquals(item1NewVersion, result.getVersion());
    assertEquals(item1Updated, result.getItem());

    // The other item should still exist
    ItemDescriptor result2 = store.get(TEST_ITEMS, item2Key);
    assertNotNull(result2);
    assertEquals(item2Version, result2.getVersion());
    assertEquals(item2, result2.getItem());
  }

  @Test
  public void applyWithPartialChangeSetCanDeleteItems() {
    initStore();

    // When applying partial changeset, include deleted item and keep other items
    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>(item1Key, ItemDescriptor.deletedItem(item1Version + 10)),
                        new AbstractMap.SimpleEntry<>(item2Key, new ItemDescriptor(item2Version, item2)) // Must include all items in kind
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Item should be marked as deleted
    ItemDescriptor result = store.get(TEST_ITEMS, item1Key);
    assertNotNull(result);
    assertNull(result.getItem());
    assertEquals(item1Version + 10, result.getVersion());

    // The other item should still exist
    ItemDescriptor result2 = store.get(TEST_ITEMS, item2Key);
    assertNotNull(result2);
    assertEquals(item2Version, result2.getVersion());
    assertEquals(item2, result2.getItem());
  }

  @Test
  public void applyWithPartialChangeSetUpdatesSelector() {
    initStore();
    Selector initialSelector = typedStore().getSelector();

    Selector newSelector = Selector.make(99, "new-state");
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        newSelector,
        ImmutableList.of(),
        null,
        true
    );

    typedStore().apply(changeSet);

    assertTrue(initialSelector.getVersion() != typedStore().getSelector().getVersion());
    assertEquals(newSelector.getVersion(), typedStore().getSelector().getVersion());
    assertEquals(newSelector.getState(), typedStore().getSelector().getState());
  }

  @Test
  public void applyWithNoneChangeSetDoesNotModifyData() {
    initStore();

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.None,
        Selector.make(5, "state5"),
        ImmutableList.of(),
        null,
        true
    );

    typedStore().apply(changeSet);

    // Data should remain unchanged
    ItemDescriptor result1 = store.get(TEST_ITEMS, item1Key);
    assertNotNull(result1);
    assertEquals(item1Version, result1.getVersion());
    assertEquals(item1, result1.getItem());

    ItemDescriptor result2 = store.get(TEST_ITEMS, item2Key);
    assertNotNull(result2);
    assertEquals(item2Version, result2.getVersion());
    assertEquals(item2, result2.getItem());
  }

  @Test
  public void applyWithFullChangeSetHandlesMultipleDataKinds() {
    TestItem updatedItem1 = new TestItem("key1", "item1", 1);
    TestItem updatedItem2 = new TestItem("key2", "item2", 2);

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key1", new ItemDescriptor(1, updatedItem1))
                    )
                )
            ),
            new AbstractMap.SimpleEntry<>(
                OTHER_TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(2, updatedItem2))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Both kinds should have data
    ItemDescriptor result1 = store.get(TEST_ITEMS, "key1");
    assertNotNull(result1);
    assertEquals(updatedItem1, result1.getItem());

    ItemDescriptor result2 = store.get(OTHER_TEST_ITEMS, "key2");
    assertNotNull(result2);
    assertEquals(updatedItem2, result2.getItem());
  }

  @Test
  public void applyWithPartialChangeSetHandlesMultipleDataKinds() {
    initStore();

    TestItem item3 = new TestItem("key3", "item3", 30);
    TestItem item4 = new TestItem("key4", "item4", 40);

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
                    )
                )
            ),
            new AbstractMap.SimpleEntry<>(
                OTHER_TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key4", new ItemDescriptor(40, item4))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Original TestDataKind items should still exist
    assertNotNull(store.get(TEST_ITEMS, item1Key));
    assertNotNull(store.get(TEST_ITEMS, item2Key));

    // New items should exist
    ItemDescriptor result3 = store.get(TEST_ITEMS, "key3");
    assertNotNull(result3);
    assertEquals(item3, result3.getItem());

    ItemDescriptor result4 = store.get(OTHER_TEST_ITEMS, "key4");
    assertNotNull(result4);
    assertEquals(item4, result4.getItem());
  }

  @Test
  public void applyWithPartialChangeSetPreservesUnaffectedDataKinds() {
    // Initialize with both TEST_ITEMS and OTHER_TEST_ITEMS
    com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet<ItemDescriptor> allData =
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1, item2)
            .add(OTHER_TEST_ITEMS, new TestItem("other1", "other1", 100))
            .build();
    store.init(allData);

    // Apply partial changeset that only updates TEST_ITEMS
    TestItem item3 = new TestItem("key3", "item3", 30);
    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // TEST_ITEMS should have original items plus new item
    assertNotNull(store.get(TEST_ITEMS, item1Key));
    assertNotNull(store.get(TEST_ITEMS, item2Key));
    assertNotNull(store.get(TEST_ITEMS, "key3"));

    // OTHER_TEST_ITEMS should still exist and be unchanged
    ItemDescriptor otherResult = store.get(OTHER_TEST_ITEMS, "other1");
    assertNotNull(otherResult);
    assertEquals(new TestItem("other1", "other1", 100), otherResult.getItem());
    assertEquals(100, otherResult.getVersion());
  }

  @Test
  public void applyWithFullChangeSetEmptyDataClearsStore() {
    initStore();

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        ImmutableList.of(),
        null,
        true
    );

    typedStore().apply(changeSet);

    // All items should be gone
    assertNull(store.get(TEST_ITEMS, item1Key));
    assertNull(store.get(TEST_ITEMS, item2Key));

    // But store should be initialized
    assertTrue(store.isInitialized());
  }

  @Test
  public void applyWithPartialChangeSetOnUninitializedStore() {
    assertFalse(store.isInitialized());

    TestItem item3 = new TestItem("key3", "item3", 30);
    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(1, "state1"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    // Item should be added
    ItemDescriptor result = store.get(TEST_ITEMS, "key3");
    assertNotNull(result);
    assertEquals(item3, result.getItem());

    // Store should still not be marked as initialized (partial updates don't initialize)
    assertFalse(store.isInitialized());
  }

  @Test
  public void applyWithMultipleItemsInSameKind() {
    TestItem localItem1 = new TestItem("key1", "item1", 10);
    TestItem localItem2 = new TestItem("key2", "item2", 20);
    TestItem item3 = new TestItem("key3", "item3", 30);

    ImmutableList<Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>>> changeSetData =
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(
                TEST_ITEMS,
                new com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<>(
                    ImmutableList.of(
                        new AbstractMap.SimpleEntry<>("key1", new ItemDescriptor(10, localItem1)),
                        new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(20, localItem2)),
                        new AbstractMap.SimpleEntry<>("key3", new ItemDescriptor(30, item3))
                    )
                )
            )
        );

    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        changeSetData,
        null,
        true
    );

    typedStore().apply(changeSet);

    Map<String, ItemDescriptor> allItems = toItemsMap(store.getAll(TEST_ITEMS));
    assertEquals(3, allItems.size());
  }

  // ExportAllData tests

  @Test
  public void exportAllDataReturnsCompleteSnapshot() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    TestItem item2 = new TestItem("key2", "item2", 2);
    TestItem item3 = new TestItem("key3", "item3", 3);

    store.init(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1, item2)
        .add(OTHER_TEST_ITEMS, item3)
        .build());

    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();

    // Should have both data kinds
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      count++;
    }
    assertEquals(2, count);

    // Verify TEST_ITEMS data
    Map<String, ItemDescriptor> testKindData = null;
    Map<String, ItemDescriptor> otherKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      } else if (entry.getKey() == OTHER_TEST_ITEMS) {
        otherKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertNotNull(otherKindData);
    assertEquals(2, testKindData.size());
    assertEquals(item1, testKindData.get("key1").getItem());
    assertEquals(1, testKindData.get("key1").getVersion());
    assertEquals(item2, testKindData.get("key2").getItem());
    assertEquals(2, testKindData.get("key2").getVersion());

    // Verify OTHER_TEST_ITEMS data
    assertEquals(1, otherKindData.size());
    assertEquals(item3, otherKindData.get("key3").getItem());
    assertEquals(3, otherKindData.get("key3").getVersion());
    
    // Verify shouldPersist is preserved (DataBuilder.build() returns shouldPersist=true)
    assertTrue(exported.shouldPersist());
  }

  @Test
  public void exportAllDataWithEmptyStoreReturnsEmptyDataSet() {
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();

    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      count++;
    }
    assertEquals(0, count);
    
    // Empty store should default to shouldPersist=false (initial default value)
    assertFalse(exported.shouldPersist());
  }

  @Test
  public void exportAllDataWithDeletedItemsIncludesDeletedItems() {
    TestItem item1 = new TestItem("key1", "item1", 1);

    store.init(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1)
        .build());
    
    // Delete an item
    store.upsert(TEST_ITEMS, "key2", ItemDescriptor.deletedItem(2));

    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();

    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(2, testKindData.size());

    // Regular item
    ItemDescriptor regularItem = testKindData.get("key1");
    assertEquals(item1, regularItem.getItem());
    assertEquals(1, regularItem.getVersion());

    // Deleted item
    ItemDescriptor deletedItem = testKindData.get("key2");
    assertNull(deletedItem.getItem());
    assertEquals(2, deletedItem.getVersion());
    
    // Verify shouldPersist is preserved from init (DataBuilder.build() returns shouldPersist=true)
    assertTrue(exported.shouldPersist());
  }

  @Test
  public void exportAllDataIsThreadSafe() throws Exception {
    // Initialize with some data
    TestItem item1 = new TestItem("key1", "item1", 1);
    store.init(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1)
        .build());

    // Start export in background thread
    java.util.concurrent.CountDownLatch exportStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch continueExport = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.Future<FullDataSet<ItemDescriptor>> exportTask = 
        java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
          exportStarted.countDown();
          try {
            continueExport.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return typedStore().exportAll();
        });

    // Wait for export to start
    exportStarted.await();

    // Try to perform concurrent upsert (should block until export completes)
    TestItem item2 = new TestItem("key2", "item2", 2);
    java.util.concurrent.Future<?> upsertTask = 
        java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
          store.upsert(TEST_ITEMS, "key2", new ItemDescriptor(2, item2));
        });

    // Let export continue
    continueExport.countDown();

    // Wait for both operations to complete
    FullDataSet<ItemDescriptor> exported = exportTask.get();
    upsertTask.get();

    // Export should have completed successfully
    // Either key2 is in the export (upsert happened before export) or not (upsert happened after)
    // Both are valid outcomes as long as no exception was thrown
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    if (testKindData != null && !testKindData.isEmpty()) {
      assertTrue(testKindData.containsKey("key1"));
      // key2 may or may not be present depending on timing
    }

    // Verify store now has both items
    assertEquals(item1, store.get(TEST_ITEMS, "key1").getItem());
    assertEquals(item2, store.get(TEST_ITEMS, "key2").getItem());
  }

  @Test
  public void exportAllDataReturnsImmutableSnapshot() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    store.init(new DataStoreTestTypes.DataBuilder()
        .add(TEST_ITEMS, item1)
        .build());

    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();

    // Modify store after export
    TestItem item2 = new TestItem("key2", "item2", 2);
    store.upsert(TEST_ITEMS, "key2", new ItemDescriptor(2, item2));

    // Exported data should not be affected
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(1, testKindData.size());
    assertEquals("key1", testKindData.keySet().iterator().next());
    
    // Verify shouldPersist is preserved from init (DataBuilder.build() returns shouldPersist=true)
    assertTrue(exported.shouldPersist());
  }

  @Test
  public void exportAllPreservesShouldPersistFromInitWithFalse() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    
    // Initialize with shouldPersist=false (e.g., from file data source)
    FullDataSet<ItemDescriptor> initData = new FullDataSet<>(
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1)
            .build().getData(),
        false // shouldPersist=false
    );
    store.init(initData);
    
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();
    
    // Verify shouldPersist=false is preserved
    assertFalse(exported.shouldPersist());
    
    // Verify data is correct
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(1, testKindData.size());
    assertEquals(item1, testKindData.get("key1").getItem());
  }

  @Test
  public void exportAllPreservesShouldPersistFromInitWithTrue() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    
    // Initialize with shouldPersist=true (e.g., from polling/streaming data source)
    FullDataSet<ItemDescriptor> initData = new FullDataSet<>(
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1)
            .build().getData(),
        true // shouldPersist=true
    );
    store.init(initData);
    
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();
    
    // Verify shouldPersist=true is preserved
    assertTrue(exported.shouldPersist());
    
    // Verify data is correct
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(1, testKindData.size());
    assertEquals(item1, testKindData.get("key1").getItem());
  }

  @Test
  public void exportAllPreservesShouldPersistWhenApplyFalseOverwritesTrue() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    TestItem item2 = new TestItem("key2", "item2", 2);
    
    // Initialize with shouldPersist=true
    FullDataSet<ItemDescriptor> initData = new FullDataSet<>(
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1)
            .build().getData(),
        true // shouldPersist=true
    );
    store.init(initData);
    
    // Apply change set with shouldPersist=false (e.g., file data source overwrites)
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(2, item2))
        ))
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        false // shouldPersist=false
    );
    typedStore().apply(changeSet);
    
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();
    
    // Verify shouldPersist=false is preserved (most recent apply overwrites)
    assertFalse(exported.shouldPersist());
    
    // Verify data includes both items
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(2, testKindData.size());
    assertEquals(item1, testKindData.get("key1").getItem());
    assertEquals(item2, testKindData.get("key2").getItem());
  }

  @Test
  public void exportAllPreservesShouldPersistWhenApplyTrueOverwritesFalse() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    TestItem item2 = new TestItem("key2", "item2", 2);
    
    // Initialize with shouldPersist=false
    FullDataSet<ItemDescriptor> initData = new FullDataSet<>(
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1)
            .build().getData(),
        false // shouldPersist=false
    );
    store.init(initData);
    
    // Apply change set with shouldPersist=true (e.g., polling data source overwrites file data)
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(2, item2))
        ))
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        true // shouldPersist=true
    );
    typedStore().apply(changeSet);
    
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();
    
    // Verify shouldPersist=true is preserved (most recent apply overwrites)
    assertTrue(exported.shouldPersist());
    
    // Verify data includes both items
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(2, testKindData.size());
    assertEquals(item1, testKindData.get("key1").getItem());
    assertEquals(item2, testKindData.get("key2").getItem());
  }

  @Test
  public void exportAllPreservesShouldPersistWhenFullChangeSetOverwrites() {
    TestItem item1 = new TestItem("key1", "item1", 1);
    TestItem item2 = new TestItem("key2", "item2", 2);
    
    // Initialize with shouldPersist=true
    FullDataSet<ItemDescriptor> initData = new FullDataSet<>(
        new DataStoreTestTypes.DataBuilder()
            .add(TEST_ITEMS, item1)
            .build().getData(),
        true // shouldPersist=true
    );
    store.init(initData);
    
    // Apply full change set with shouldPersist=false
    Map<DataKind, KeyedItems<ItemDescriptor>> changeSetData = ImmutableMap.of(
        TEST_ITEMS,
        new KeyedItems<>(ImmutableList.of(
            new AbstractMap.SimpleEntry<>("key2", new ItemDescriptor(2, item2))
        ))
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(2, "state2"),
        changeSetData.entrySet(),
        null,
        false // shouldPersist=false
    );
    typedStore().apply(changeSet);
    
    FullDataSet<ItemDescriptor> exported = typedStore().exportAll();
    
    // Verify shouldPersist=false is preserved (most recent apply overwrites)
    assertFalse(exported.shouldPersist());
    
    // Verify data only includes item2 (full change set replaces all data)
    Map<String, ItemDescriptor> testKindData = null;
    for (Map.Entry<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind,
        com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems<ItemDescriptor>> entry : exported.getData()) {
      if (entry.getKey() == TEST_ITEMS) {
        testKindData = toItemsMap(entry.getValue());
      }
    }
    assertNotNull(testKindData);
    assertEquals(1, testKindData.size());
    assertEquals(item2, testKindData.get("key2").getItem());
  }
}
