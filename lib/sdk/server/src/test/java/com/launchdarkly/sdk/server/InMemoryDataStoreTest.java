package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.launchdarkly.sdk.server.subsystems.DataStore;

import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.Snapshot;
import org.junit.Test;

import static com.launchdarkly.sdk.server.DataStoreTestTypes.OTHER_TEST_ITEMS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.TEST_ITEMS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@SuppressWarnings("javadoc")
public class InMemoryDataStoreTest extends DataStoreTestBase {

  @Override
  protected DataStore makeStore() {
    return new InMemoryDataStore();
  }
  
  @Test
  public void cacheStatsAreNull() {
    assertNull(makeStore().getCacheStats());
  }

  @Test
  public void updateMultipleOfTheSameKind() {
    store.init(new DataStoreTestTypes.DataBuilder().add(TEST_ITEMS, item1, item2)
      .add(OTHER_TEST_ITEMS, otherItem1).build());
    DataStoreTypes.ItemDescriptor deletedItem = DataStoreTypes.ItemDescriptor.deletedItem(item1.version + 1);
    DataStoreTypes.ItemDescriptor updatedItem = item2.withVersion(item2.version + 1).toItemDescriptor();
    DataStoreTypes.ItemDescriptor newItem = new DataStoreTestTypes.TestItem("new-name", "new-key", 99).toItemDescriptor();

    ImmutableList.Builder<DataStore.Update> updates = ImmutableList.builder();

    updates.add(new DataStore.Update(
      TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)updatedItem.getItem()).getKey(),
      updatedItem
    ));

    updates.add(new DataStore.Update(
      TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)newItem.getItem()).getKey(),
      newItem
    ));

    store.update(updates.build());

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> testItems = store.getAll(TEST_ITEMS);
    assertEquals(3, Iterables.size(testItems.getItems()));
    assertEquals(item1.toItemDescriptor(), store.get(TEST_ITEMS, item1.getKey()));
    assertEquals(updatedItem, store.get(TEST_ITEMS, item2.getKey()));
    assertEquals(newItem, store.get(TEST_ITEMS, "new-name"));

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> otherItems = store.getAll(OTHER_TEST_ITEMS);
    assertEquals(1, Iterables.size(otherItems.getItems()));
    assertEquals(otherItem1.toItemDescriptor(), store.get(OTHER_TEST_ITEMS, otherItem1.getKey()));
  }

  @Test
  public void updateMultipleOfDifferentKinds() {
    store.init(new DataStoreTestTypes.DataBuilder().add(TEST_ITEMS, item1, item2)
      .add(OTHER_TEST_ITEMS, otherItem1).build());
    DataStoreTypes.ItemDescriptor deletedItem = DataStoreTypes.ItemDescriptor.deletedItem(item1.version + 1);
    DataStoreTypes.ItemDescriptor updatedItem = item2.withVersion(item2.version + 1).toItemDescriptor();
    DataStoreTypes.ItemDescriptor newItem = new DataStoreTestTypes.TestItem("new-name", "new-key", 99).toItemDescriptor();

    ImmutableList.Builder<DataStore.Update> updates = ImmutableList.builder();
    updates.add(new DataStore.Update(
      TEST_ITEMS,
      item1.getKey(),
      deletedItem
    ));

    updates.add(new DataStore.Update(
      TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)updatedItem.getItem()).getKey(),
      updatedItem
    ));

    updates.add(new DataStore.Update(
      OTHER_TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)newItem.getItem()).getKey(),
      newItem
    ));

    store.update(updates.build());

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> testItems = store.getAll(TEST_ITEMS);
    assertEquals(2, Iterables.size(testItems.getItems()));
    assertEquals(deletedItem, store.get(TEST_ITEMS, item1.getKey()));
    assertEquals(updatedItem, store.get(TEST_ITEMS, item2.getKey()));

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> otherItems = store.getAll(OTHER_TEST_ITEMS);
    assertEquals(2, Iterables.size(otherItems.getItems()));
    assertEquals(otherItem1.toItemDescriptor(), store.get(OTHER_TEST_ITEMS, otherItem1.getKey()));
    assertEquals(newItem, store.get(OTHER_TEST_ITEMS, "new-name"));
  }

  @Test
  public void updateWhichAddsKind() {
    store.init(new DataStoreTestTypes.DataBuilder().add(TEST_ITEMS, item1, item2).build());
    DataStoreTypes.ItemDescriptor deletedItem = DataStoreTypes.ItemDescriptor.deletedItem(item1.version + 1);
    DataStoreTypes.ItemDescriptor updatedItem = item2.withVersion(item2.version + 1).toItemDescriptor();
    DataStoreTypes.ItemDescriptor newItem = new DataStoreTestTypes.TestItem("new-name", "new-key", 99).toItemDescriptor();

    ImmutableList.Builder<DataStore.Update> updates = ImmutableList.builder();

    updates.add(new DataStore.Update(
      OTHER_TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)newItem.getItem()).getKey(),
      newItem
    ));

    store.update(updates.build());

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> testItems = store.getAll(TEST_ITEMS);
    assertEquals(2, Iterables.size(testItems.getItems()));
    assertEquals(item1.toItemDescriptor(), store.get(TEST_ITEMS, item1.getKey()));
    assertEquals(item2.toItemDescriptor(), store.get(TEST_ITEMS, item2.getKey()));

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> otherItems = store.getAll(OTHER_TEST_ITEMS);
    assertEquals(1, Iterables.size(otherItems.getItems()));
    assertEquals(newItem, store.get(OTHER_TEST_ITEMS, "new-name"));
  }

  @Test
  public void updatesDoNotAffectSnapshots() {
    store.init(new DataStoreTestTypes.DataBuilder().add(TEST_ITEMS, item1, item2)
      .add(OTHER_TEST_ITEMS, otherItem1).build());
    DataStoreTypes.ItemDescriptor deletedItem = DataStoreTypes.ItemDescriptor.deletedItem(item1.version + 1);
    DataStoreTypes.ItemDescriptor updatedItem = item2.withVersion(item2.version + 1).toItemDescriptor();
    DataStoreTypes.ItemDescriptor newItem = new DataStoreTestTypes.TestItem("new-name", "new-key", 99).toItemDescriptor();

    ImmutableList.Builder<DataStore.Update> updates = ImmutableList.builder();
    updates.add(new DataStore.Update(
      TEST_ITEMS,
      item1.getKey(),
      deletedItem
    ));

    updates.add(new DataStore.Update(
      TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)updatedItem.getItem()).getKey(),
      updatedItem
    ));

    updates.add(new DataStore.Update(
      OTHER_TEST_ITEMS,
      ((DataStoreTestTypes.TestItem)newItem.getItem()).getKey(),
      newItem
    ));

    // Snapshot before changes are applied.
    Snapshot snapshot = store.getSnapshot();
    store.update(updates.build());

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> testItems = store.getAll(TEST_ITEMS);
    assertEquals(2, Iterables.size(testItems.getItems()));
    assertEquals(deletedItem, store.get(TEST_ITEMS, item1.getKey()));
    assertEquals(updatedItem, store.get(TEST_ITEMS, item2.getKey()));

    DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> otherItems = store.getAll(OTHER_TEST_ITEMS);
    assertEquals(2, Iterables.size(otherItems.getItems()));
    assertEquals(otherItem1.toItemDescriptor(), store.get(OTHER_TEST_ITEMS, otherItem1.getKey()));
    assertEquals(newItem, store.get(OTHER_TEST_ITEMS, "new-name"));

    assertEquals(item1.toItemDescriptor(), snapshot.get(TEST_ITEMS, item1.getKey()));
    assertEquals(item2.toItemDescriptor(), snapshot.get(TEST_ITEMS, item2.getKey()));

    assertEquals(otherItem1.toItemDescriptor(), snapshot.get(OTHER_TEST_ITEMS, otherItem1.getKey()));
    assertEquals(null, snapshot.get(OTHER_TEST_ITEMS, "new-name"));
  }
}
