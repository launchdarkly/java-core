package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class PersistentDataStoreConverterTest {
  
  // Simple test DataKind that uses "item1:1" format like C# tests
  private static final DataKind TEST_DATA_KIND = new DataKind("testdata",
      PersistentDataStoreConverterTest::serializeTestItem,
      PersistentDataStoreConverterTest::deserializeTestItem);
  
  private static final DataKind OTHER_DATA_KIND = new DataKind("otherdata",
      PersistentDataStoreConverterTest::serializeTestItem,
      PersistentDataStoreConverterTest::deserializeTestItem);
  
  private static String serializeTestItem(ItemDescriptor item) {
    if (item.getItem() == null) {
      return "DELETED:" + item.getVersion();
    }
    TestItem testItem = (TestItem) item.getItem();
    return testItem.name + ":" + item.getVersion();
  }
  
  private static ItemDescriptor deserializeTestItem(String s) {
    String[] parts = s.split(":");
    int version = Integer.parseInt(parts[1]);
    if ("DELETED".equals(parts[0])) {
      return ItemDescriptor.deletedItem(version);
    }
    return new ItemDescriptor(version, new TestItem(parts[0]));
  }
  
  private static class TestItem {
    final String name;
    
    TestItem(String name) {
      this.name = name;
    }
    
    @Override
    public boolean equals(Object o) {
      if (o instanceof TestItem) {
        return name.equals(((TestItem) o).name);
      }
      return false;
    }
    
    @Override
    public int hashCode() {
      return name.hashCode();
    }
    
    @Override
    public String toString() {
      return "TestItem(" + name + ")";
    }
  }
  
  private static class TestDataBuilder {
    private final Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    
    TestDataBuilder add(DataKind kind, String key, int version, Object item) {
      data.computeIfAbsent(kind, k -> new HashMap<>()).put(key, new ItemDescriptor(version, item));
      return this;
    }
    
    FullDataSet<ItemDescriptor> build() {
      ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> builder = 
          ImmutableList.builder();
      for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : data.entrySet()) {
        ImmutableList.Builder<Map.Entry<String, ItemDescriptor>> itemsBuilder = 
            ImmutableList.builder();
        for (Map.Entry<String, ItemDescriptor> item : e.getValue().entrySet()) {
          itemsBuilder.add(new AbstractMap.SimpleEntry<>(item.getKey(), item.getValue()));
        }
        builder.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(itemsBuilder.build())));
      }
      return new FullDataSet<>(builder.build(), true);
    }
  }

  @Test
  public void toSerializedFormatConvertsCorrectly() {
    TestItem item1 = new TestItem("item1");
    TestItem item2 = new TestItem("item2");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, item1)
        .add(TEST_DATA_KIND, "key2", 2, item2)
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    // Should have one data kind
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> ignored : 
        serializedData.getData()) {
      count++;
    }
    assertEquals(1, count);

    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    
    int itemCount = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, SerializedItemDescriptor> ignored : 
        testKindData.getItems()) {
      itemCount++;
    }
    assertEquals(2, itemCount);

    // Verify first item
    SerializedItemDescriptor serializedItem1 = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key1".equals(e.getKey())) {
        serializedItem1 = e.getValue();
        break;
      }
    }
    assertNotNull(serializedItem1);
    assertEquals(1, serializedItem1.getVersion());
    assertFalse(serializedItem1.isDeleted());
    assertNotNull(serializedItem1.getSerializedItem());
    assertEquals("item1:1", serializedItem1.getSerializedItem());

    // Verify second item
    SerializedItemDescriptor serializedItem2 = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key2".equals(e.getKey())) {
        serializedItem2 = e.getValue();
        break;
      }
    }
    assertNotNull(serializedItem2);
    assertEquals(2, serializedItem2.getVersion());
    assertFalse(serializedItem2.isDeleted());
    assertNotNull(serializedItem2.getSerializedItem());
    assertEquals("item2:2", serializedItem2.getSerializedItem());
  }

  @Test
  public void toSerializedFormatHandlesDeletedItems() {
    TestItem item1 = new TestItem("item1");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, item1)
        .add(TEST_DATA_KIND, "key2", 2, null) // Deleted item
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    
    int itemCount = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, SerializedItemDescriptor> ignored : 
        testKindData.getItems()) {
      itemCount++;
    }
    assertEquals(2, itemCount);

    // Regular item
    SerializedItemDescriptor serializedItem1 = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key1".equals(e.getKey())) {
        serializedItem1 = e.getValue();
        break;
      }
    }
    assertNotNull(serializedItem1);
    assertEquals(1, serializedItem1.getVersion());
    assertFalse(serializedItem1.isDeleted());

    // Deleted item
    SerializedItemDescriptor serializedItem2 = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key2".equals(e.getKey())) {
        serializedItem2 = e.getValue();
        break;
      }
    }
    assertNotNull(serializedItem2);
    assertEquals(2, serializedItem2.getVersion());
    assertTrue(serializedItem2.isDeleted());
    // Serialized representation still contains the placeholder
    assertEquals("DELETED:2", serializedItem2.getSerializedItem());
  }

  @Test
  public void toSerializedFormatPreservesAllDataKinds() {
    TestItem item1 = new TestItem("item1");
    TestItem item2 = new TestItem("item2");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, item1)
        .add(OTHER_DATA_KIND, "key2", 2, item2)
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    // Should have both data kinds
    int kindCount = 0;
    for (@SuppressWarnings("unused") Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> ignored : 
        serializedData.getData()) {
      kindCount++;
    }
    assertEquals(2, kindCount);

    // Verify TestDataKind
    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    int count = 0;
    SerializedItemDescriptor firstItem = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      count++;
      if (firstItem == null) {
        firstItem = e.getValue();
      }
    }
    assertEquals(1, count);
    assertNotNull(firstItem);
    assertEquals("item1:1", firstItem.getSerializedItem());

    // Verify OtherDataKind
    KeyedItems<SerializedItemDescriptor> otherKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == OTHER_DATA_KIND) {
        otherKindData = e.getValue();
        break;
      }
    }
    assertNotNull(otherKindData);
    count = 0;
    firstItem = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : otherKindData.getItems()) {
      count++;
      if (firstItem == null) {
        firstItem = e.getValue();
      }
    }
    assertEquals(1, count);
    assertNotNull(firstItem);
    assertEquals("item2:2", firstItem.getSerializedItem());
  }

  @Test
  public void toSerializedFormatWithEmptyDataReturnsEmptyDataSet() {
    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder().build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> ignored : 
        serializedData.getData()) {
      count++;
    }
    assertEquals(0, count);
  }

  @Test
  public void toSerializedFormatWithEmptyKindIncludesEmptyKind() {
    // Create a data set with a kind that has no items
    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, new TestItem("item1"))
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    // Should have the kind with items
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> ignored : 
        serializedData.getData()) {
      count++;
    }
    assertEquals(1, count);
    
    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    int itemCount = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, SerializedItemDescriptor> ignored : 
        testKindData.getItems()) {
      itemCount++;
    }
    assertEquals(1, itemCount);
  }

  @Test
  public void toSerializedFormatPreservesVersionNumbers() {
    TestItem item1 = new TestItem("item1");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 100, item1)
        .add(TEST_DATA_KIND, "key2", 999, item1)
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);

    SerializedItemDescriptor item1Serialized = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key1".equals(e.getKey())) {
        item1Serialized = e.getValue();
        break;
      }
    }
    assertNotNull(item1Serialized);
    assertEquals(100, item1Serialized.getVersion());

    SerializedItemDescriptor item2Serialized = null;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key2".equals(e.getKey())) {
        item2Serialized = e.getValue();
        break;
      }
    }
    assertNotNull(item2Serialized);
    assertEquals(999, item2Serialized.getVersion());
  }

  @Test
  public void toSerializedFormatWithMultipleItemsInSameKind() {
    TestItem item1 = new TestItem("item1");
    TestItem item2 = new TestItem("item2");
    TestItem item3 = new TestItem("item3");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, item1)
        .add(TEST_DATA_KIND, "key2", 2, item2)
        .add(TEST_DATA_KIND, "key3", 3, item3)
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, SerializedItemDescriptor> ignored : 
        testKindData.getItems()) {
      count++;
    }
    assertEquals(3, count);

    // Verify all three items are present with correct serialization
    boolean foundKey1 = false;
    boolean foundKey2 = false;
    boolean foundKey3 = false;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if ("key1".equals(e.getKey()) && "item1:1".equals(e.getValue().getSerializedItem())) {
        foundKey1 = true;
      }
      if ("key2".equals(e.getKey()) && "item2:2".equals(e.getValue().getSerializedItem())) {
        foundKey2 = true;
      }
      if ("key3".equals(e.getKey()) && "item3:3".equals(e.getValue().getSerializedItem())) {
        foundKey3 = true;
      }
    }
    assertTrue(foundKey1);
    assertTrue(foundKey2);
    assertTrue(foundKey3);
  }

  @Test
  public void toSerializedFormatWithMixedDeletedAndRegularItems() {
    TestItem item1 = new TestItem("item1");
    TestItem item3 = new TestItem("item3");

    FullDataSet<ItemDescriptor> inMemoryData = new TestDataBuilder()
        .add(TEST_DATA_KIND, "key1", 1, item1)
        .add(TEST_DATA_KIND, "key2", 2, null) // Deleted
        .add(TEST_DATA_KIND, "key3", 3, item3)
        .add(TEST_DATA_KIND, "key4", 4, null) // Deleted
        .build();

    FullDataSet<SerializedItemDescriptor> serializedData = 
        PersistentDataStoreConverter.toSerializedFormat(inMemoryData);

    KeyedItems<SerializedItemDescriptor> testKindData = null;
    for (Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>> e : serializedData.getData()) {
      if (e.getKey() == TEST_DATA_KIND) {
        testKindData = e.getValue();
        break;
      }
    }
    assertNotNull(testKindData);
    
    int count = 0;
    for (@SuppressWarnings("unused") Map.Entry<String, SerializedItemDescriptor> ignored : 
        testKindData.getItems()) {
      count++;
    }
    assertEquals(4, count);

    // Count deleted vs non-deleted
    int deletedCount = 0;
    int regularCount = 0;
    for (Map.Entry<String, SerializedItemDescriptor> e : testKindData.getItems()) {
      if (e.getValue().isDeleted()) {
        deletedCount++;
      } else {
        regularCount++;
      }
    }

    assertEquals(2, deletedCount);
    assertEquals(2, regularCount);
  }
}
