package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider.CacheStats;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.Snapshot;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A thread-safe, versioned store for feature flags and related data based on a
 * {@link HashMap}. This is the default implementation of {@link DataStore}.
 * 
 * As of version 5.0.0, this is package-private; applications must use the factory method
 * {@link Components#inMemoryDataStore()}.
 */
class InMemoryDataStore implements DataStore {

  private static class TransactionSnapshot implements Snapshot {
    private Map<DataKind, Map<String, ItemDescriptor>> snapshot;

    public TransactionSnapshot(ImmutableMap<DataKind, Map<String, ItemDescriptor>> snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      Map<String, ItemDescriptor> items = snapshot.get(kind);
      if (items == null) {
        return null;
      }
      return items.get(key);
    }

    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      Map<String, ItemDescriptor> items = snapshot.get(kind);
      if (items == null) {
        return new KeyedItems<>(null);
      }
      return new KeyedItems<>(ImmutableList.copyOf(items.entrySet()));
    }
  }

  private volatile ImmutableMap<DataKind, Map<String, ItemDescriptor>> allData = ImmutableMap.of();
  private volatile boolean initialized = false;
  private Object writeLock = new Object();

  @Override
  public void init(FullDataSet<ItemDescriptor> allData) {
    synchronized (writeLock) {
      ImmutableMap.Builder<DataKind, Map<String, ItemDescriptor>> newData = ImmutableMap.builder();
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> entry: allData.getData()) {
        newData.put(entry.getKey(), ImmutableMap.copyOf(entry.getValue().getItems()));
      }
      this.allData = newData.build(); // replaces the entire map atomically
      this.initialized = true;
    }
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
      // The following logic is necessary because the maps contained in the builder are immutable, therefore we
      // need to use existing maps, or replace them with new maps, but we cannot update the keys in the existing
      // maps. You also cannot put the same key multiple times into the builder.
      // TODO: Could this be simplified by using buildKeepingLast.
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

  @Override
  public boolean update(ImmutableList<Update> updates) {
    synchronized (writeLock) {
      // For any updates make a builder for that kind of data if it doesn't exist
      // (the first update to that kind encountered will create the builder). The builder is based on the original
      // items if there are any. After this we will have a builder for any kinds with changes, and will not for any
      // kinds without. Then we make a top-level container from merging the un-changed kinds and built values from
      // the changed kinds.
      Map<DataKind, ImmutableMap.Builder<String, ItemDescriptor>> builders = new HashMap<>();
      for (Update update : updates) {
        if(!builders.containsKey(update.getKind())) {
          ImmutableMap.Builder<String, ItemDescriptor> builder = ImmutableMap.builder();
          Map<String, ItemDescriptor> existingData = allData.get(update.getKind());
          if(existingData != null) {
            builder.putAll(existingData);
          }
          builders.put(update.getKind(), builder);
        }
        ImmutableMap.Builder<String, ItemDescriptor> builder = builders.get(update.getKind());
        // When doing transactional updates we do not need to inspect the version of the item.
        // The updates need to be a set that updates us from our current payload version to the
        // new payload version
        builder.put(update.getKey(), update.getItem());
      }

      Set<DataKind> kindSet = new HashSet<>();
      kindSet.addAll(allData.keySet());
      kindSet.addAll(builders.keySet());

      ImmutableMap.Builder<DataKind, Map<String, ItemDescriptor>> newData = ImmutableMap.builder();
      for (DataKind kind: kindSet) {
        if(builders.containsKey(kind)) {
          // Use the updated data.
          newData.put(kind, builders.get(kind).buildKeepingLast());
        } else {
          // Use the original data. If the key was not in the builders, then it has to have been in the original data.
          newData.put(kind, allData.get(kind));
        }
      }

      // Swap all data with the new data.
      allData = newData.build();
    }
    return true;
  }

  @Override
  public Snapshot getSnapshot() {
    return new TransactionSnapshot(allData);
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
}
