package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.SerializedItemDescriptor;

import java.util.AbstractMap;
import java.util.Map;

/**
 * Utility for converting between in-memory and serialized persistent data store formats.
 */
final class PersistentDataStoreConverter {
  private PersistentDataStoreConverter() {
    // Utility class - prevent instantiation
  }

  /**
   * Converts a FullDataSet of ItemDescriptor to SerializedItemDescriptor format.
   * 
   * @param inMemoryData the in-memory data to convert
   * @return a FullDataSet in serialized format suitable for persistent stores
   */
  static FullDataSet<SerializedItemDescriptor> toSerializedFormat(
      FullDataSet<ItemDescriptor> inMemoryData) {
    ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<SerializedItemDescriptor>>> builder = 
        ImmutableList.builder();

    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : inMemoryData.getData()) {
      DataKind kind = kindEntry.getKey();
      KeyedItems<ItemDescriptor> items = kindEntry.getValue();

      builder.add(new AbstractMap.SimpleEntry<>(
          kind,
          serializeAll(kind, items)
      ));
    }

    return new FullDataSet<>(builder.build());
  }

  /**
   * Serializes a single item descriptor.
   * 
   * @param kind the data kind
   * @param itemDesc the item descriptor to serialize
   * @return a serialized item descriptor
   */
  static SerializedItemDescriptor serialize(DataKind kind, ItemDescriptor itemDesc) {
    boolean isDeleted = itemDesc.getItem() == null;
    return new SerializedItemDescriptor(itemDesc.getVersion(), isDeleted, kind.serialize(itemDesc));
  }

  /**
   * Serializes all items of a given DataKind from a KeyedItems collection.
   * 
   * @param kind the data kind
   * @param items the items to serialize
   * @return keyed items in serialized format
   */
  static KeyedItems<SerializedItemDescriptor> serializeAll(
      DataKind kind,
      KeyedItems<ItemDescriptor> items) {
    ImmutableList.Builder<Map.Entry<String, SerializedItemDescriptor>> itemsBuilder = 
        ImmutableList.builder();
    for (Map.Entry<String, ItemDescriptor> e : items.getItems()) {
      itemsBuilder.add(new AbstractMap.SimpleEntry<>(e.getKey(), serialize(kind, e.getValue())));
    }
    return new KeyedItems<>(itemsBuilder.build());
  }

  /**
   * Deserializes a single item descriptor.
   * 
   * @param kind the data kind
   * @param serializedItemDesc the serialized item descriptor
   * @return a deserialized item descriptor
   */
  static ItemDescriptor deserialize(DataKind kind, SerializedItemDescriptor serializedItemDesc) {
    if (serializedItemDesc.isDeleted() || serializedItemDesc.getSerializedItem() == null) {
      return ItemDescriptor.deletedItem(serializedItemDesc.getVersion());
    }
    ItemDescriptor deserializedItem = kind.deserialize(serializedItemDesc.getSerializedItem());
    if (serializedItemDesc.getVersion() == 0 || 
        serializedItemDesc.getVersion() == deserializedItem.getVersion() ||
        deserializedItem.getItem() == null) {
      return deserializedItem;
    }
    // If the store gave us a version number that isn't what was encoded in the object, trust it
    return new ItemDescriptor(serializedItemDesc.getVersion(), deserializedItem.getItem());
  }
}
