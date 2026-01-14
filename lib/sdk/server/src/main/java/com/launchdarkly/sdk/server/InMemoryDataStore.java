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
import com.launchdarkly.sdk.server.subsystems.TransactionalDataStore;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A thread-safe, versioned store for feature flags and related data based on a
 * {@link HashMap}. This is the default implementation of {@link DataStore}.
 * 
 * As of version 5.0.0, this is package-private; applications must use the factory method
 * {@link Components#inMemoryDataStore()}.
 */
class InMemoryDataStore implements DataStore, TransactionalDataStore, CacheExporter {
  private volatile ImmutableMap<DataKind, Map<String, ItemDescriptor>> allData = ImmutableMap.of();
  private volatile boolean initialized = false;
  private Object writeLock = new Object();
  private final Object selectorLock = new Object();
  private volatile Selector selector = Selector.EMPTY;

  @Override
  public void init(FullDataSet<ItemDescriptor> allData) {
    applyFullPayload(allData.getData(), null, Selector.EMPTY);
  }

  @Override
  public ItemDescriptor get(DataKind kind, String key) {
    Map<String, ItemDescriptor> items = allData.get(kind);
    if (items == null) {
      return null;
    }
    return items.get(key);
  }

  @Override
  public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
    Map<String, ItemDescriptor> items = allData.get(kind);
    if (items == null) {
      return new KeyedItems<>(null);
    }
    return new KeyedItems<>(ImmutableList.copyOf(items.entrySet()));
  }

  @Override
  public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
    synchronized (writeLock) {
      Map<String, ItemDescriptor> existingItems = this.allData.get(kind);
      ItemDescriptor oldItem = null;
      if (existingItems != null) {
        oldItem = existingItems.get(key);
        if (oldItem != null && oldItem.getVersion() >= item.getVersion()) {
          return false;
        }
      }
      // The following logic is necessary because ImmutableMap.Builder doesn't support overwriting an existing key
      ImmutableMap.Builder<DataKind, Map<String, ItemDescriptor>> newData = ImmutableMap.builder();
      for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e: this.allData.entrySet()) {
        if (!e.getKey().equals(kind)) {
          newData.put(e.getKey(), e.getValue());
        }
      }
      if (existingItems == null) {
        newData.put(kind, ImmutableMap.of(key, item));
      } else {
        ImmutableMap.Builder<String, ItemDescriptor> itemsBuilder = ImmutableMap.builder();
        if (oldItem == null) {
          itemsBuilder.putAll(existingItems);
        } else {
          for (Map.Entry<String, ItemDescriptor> e: existingItems.entrySet()) {
            if (!e.getKey().equals(key)) {
              itemsBuilder.put(e.getKey(), e.getValue());
            }
          }
        }
        itemsBuilder.put(key, item);
        newData.put(kind, itemsBuilder.build());
      }
      this.allData = newData.build(); // replaces the entire map atomically
      return true;
    }
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }
  
  @Override
  public boolean isStatusMonitoringEnabled() {
    return false;
  }
  
  @Override
  public CacheStats getCacheStats() {
    return null;
  }
  
  /**
   * Does nothing; this class does not have any resources to release
   *
   * @throws IOException will never happen
   */
  @Override
  public void close() throws IOException {
    return;
  }

  @Override
  public void apply(ChangeSet<ItemDescriptor> changeSet) {
    switch (changeSet.getType()) {
      case Full:
        applyFullPayload(changeSet.getData(), changeSet.getEnvironmentId(), changeSet.getSelector());
        break;
      case Partial:
        applyPartialData(changeSet.getData(), changeSet.getSelector());
        break;
      case None:
        break;
      default:
        // This represents an implementation error. The ChangeSetType was extended, but handling was not
        // added.
        throw new IllegalArgumentException("Unknown ChangeSetType: " + changeSet.getType());
    }
  }

  @Override
  public Selector getSelector() {
    synchronized (selectorLock) {
      return selector;
    }
  }

  private void setSelector(Selector newSelector) {
    synchronized (selectorLock) {
      selector = newSelector;
    }
  }

  private void applyPartialData(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data,
      Selector selector) {
    synchronized (writeLock) {
      // Build the complete updated dictionary before assigning to Items for transactional update
      ImmutableMap.Builder<DataKind, Map<String, ItemDescriptor>> itemsBuilder = ImmutableMap.builder();
      
      // First, collect all kinds that will be updated
      java.util.Set<DataKind> updatedKinds = new java.util.HashSet<>();
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindItemsPair : data) {
        updatedKinds.add(kindItemsPair.getKey());
      }
      
      // Add all existing kinds that are NOT being updated
      for (Map.Entry<DataKind, Map<String, ItemDescriptor>> existingEntry : allData.entrySet()) {
        if (!updatedKinds.contains(existingEntry.getKey())) {
          itemsBuilder.put(existingEntry.getKey(), existingEntry.getValue());
        }
      }

      // Now process the updated kinds
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindItemsPair : data) {
        DataKind kind = kindItemsPair.getKey();
        // Use HashMap to allow overwriting, then convert to ImmutableMap
        Map<String, ItemDescriptor> kindMap = new HashMap<>();

        Map<String, ItemDescriptor> itemsOfKind = allData.get(kind);
        if (itemsOfKind != null) {
          kindMap.putAll(itemsOfKind);
        }

        // Overwrite/add items from the change set (HashMap.put overwrites existing keys)
        for (Map.Entry<String, ItemDescriptor> keyValuePair : kindItemsPair.getValue().getItems()) {
          kindMap.put(keyValuePair.getKey(), keyValuePair.getValue());
        }

        itemsBuilder.put(kind, ImmutableMap.copyOf(kindMap));
      }

      allData = itemsBuilder.build();
      setSelector(selector);
    }
  }

  private void applyFullPayload(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data,
      String environmentId, Selector selector) {
    ImmutableMap.Builder<DataKind, Map<String, ItemDescriptor>> itemsBuilder = ImmutableMap.builder();

    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : data) {
      ImmutableMap.Builder<String, ItemDescriptor> kindItemsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, ItemDescriptor> e1 : kindEntry.getValue().getItems()) {
        kindItemsBuilder.put(e1.getKey(), e1.getValue());
      }
      itemsBuilder.put(kindEntry.getKey(), kindItemsBuilder.build());
    }

    ImmutableMap<DataKind, Map<String, ItemDescriptor>> newItems = itemsBuilder.build();

    synchronized (writeLock) {
      allData = newItems;
      initialized = true;
      setSelector(selector);
    }
  }

  @Override
  public FullDataSet<ItemDescriptor> exportAll() {
    synchronized (writeLock) {
      ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> builder = ImmutableList.builder();

      for (Map.Entry<DataKind, Map<String, ItemDescriptor>> kindEntry : allData.entrySet()) {
        builder.add(new AbstractMap.SimpleEntry<>(
            kindEntry.getKey(),
            new KeyedItems<>(ImmutableList.copyOf(kindEntry.getValue().entrySet()))
        ));
      }

      return new FullDataSet<>(builder.build());
    }
  }
}
