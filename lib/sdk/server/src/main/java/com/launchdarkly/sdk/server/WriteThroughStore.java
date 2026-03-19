package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider.CacheStats;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration.DataStoreMode;
import com.launchdarkly.sdk.server.subsystems.TransactionalDataStore;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A data store that writes through to both an in-memory store and an optional persistent store.
 * <p>
 * During initialization, reads happen from the persistent store (if present). Once an initializing
 * payload is received, reads switch to the in-memory store. Writes always go to both stores when
 * in READ_WRITE mode.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class WriteThroughStore implements DataStore, TransactionalDataStore {
  private final DataStore memoryStore;
  private final TransactionalDataStore txMemoryStore;
  private final DataStore persistentStore;
  private final boolean hasPersistence;
  private final DataStoreMode persistenceMode;
  private final AtomicBoolean hasReceivedAnInitializingPayload = new AtomicBoolean(false);
  
  private final Object activeStoreLock = new Object();
  private volatile DataStore activeReadStore;

  /**
   * Creates a new WriteThroughStore.
   * 
   * @param memoryStore the in-memory store (must implement TransactionalDataStore)
   * @param persistentStore the persistent store, or null if no persistence is configured
   * @param persistenceMode the mode for the persistent store
   */
  WriteThroughStore(DataStore memoryStore, DataStore persistentStore, DataStoreMode persistenceMode) {
    this.memoryStore = memoryStore;
    this.txMemoryStore = (TransactionalDataStore) memoryStore;
    this.persistentStore = persistentStore;
    this.hasPersistence = persistentStore != null;
    // During initialization, reads will happen from the persistent store.
    this.activeReadStore = hasPersistence ? persistentStore : memoryStore;
    this.persistenceMode = persistenceMode;
  }

  @Override
  public void init(FullDataSet<ItemDescriptor> allData) {
    memoryStore.init(allData);
    maybeSwitchStore();

    // Only write to persistent store if shouldPersist is true and store is in READ_WRITE mode
    if (hasPersistence && persistenceMode == DataStoreMode.READ_WRITE && allData.shouldPersist()) {
      persistentStore.init(allData);
    }
  }

  @Override
  public ItemDescriptor get(DataKind kind, String key) {
    return activeReadStore.get(kind, key);
  }

  @Override
  public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
    return activeReadStore.getAll(kind);
  }

  @Override
  public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
    boolean result = memoryStore.upsert(kind, key, item);
    // Note: upsert() doesn't have persist information. For legacy paths (FDv1), we always persist.
    // For FDv2 paths, this method shouldn't be called - use apply() instead which has persist info.
    if (hasPersistence && persistenceMode == DataStoreMode.READ_WRITE) {
      result = result && persistentStore.upsert(kind, key, item);
    }
    
    // We aren't going to switch from persistence on an update.
    // Currently, an upsert should not ever be the first operation on a store.
    // If selector support for persistent stores was added, then they would use the apply path.
    return result;
  }

  @Override
  public boolean isInitialized() {
    return activeReadStore.isInitialized();
  }

  @Override
  public boolean isStatusMonitoringEnabled() {
    return hasPersistence ? persistentStore.isStatusMonitoringEnabled() : false;
  }

  @Override
  public CacheStats getCacheStats() {
    return hasPersistence ? persistentStore.getCacheStats() : null;
  }

  @Override
  public void apply(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet) {
    txMemoryStore.apply(changeSet);
    maybeSwitchStore();

    // Only write to persistent store if shouldPersist is true and store is in READ_WRITE mode
    if (!hasPersistence || persistenceMode != DataStoreMode.READ_WRITE || !changeSet.shouldPersist()) {
      return;
    }

    if (persistentStore instanceof TransactionalDataStore) {
      ((TransactionalDataStore) persistentStore).apply(changeSet);
    } else {
      // If an apply fails at init, that will throw on its own, but if it fails via an upsert, then
      // we need to throw something to work with the current data source updates implementation.
      if (!applyToLegacyPersistence(changeSet)) {
        // The exception type doesn't matter here, as it will be converted to data store status.
        throw new RuntimeException("Failure to apply data set to persistent store.");
      }
    }
  }

  @Override
  public Selector getSelector() {
    return txMemoryStore.getSelector();
  }

  @Override
  public void close() throws IOException {
    memoryStore.close();
    if (hasPersistence) {
      persistentStore.close();
    }
  }

  /**
   * Switches the active read store from persistent to memory store once an initializing payload is received.
   * This transition happens once, and then subsequently we only use the memory store.
   */
  private void maybeSwitchStore() {
    if (hasReceivedAnInitializingPayload.getAndSet(true)) {
      return;
    }
    synchronized (activeStoreLock) {
      activeReadStore = memoryStore;
    }
  }

  /**
   * Applies a change set to a legacy persistent store that doesn't implement TransactionalDataStore.
   * 
   * @param sortedChangeSet the change set to apply (data will have been sorted by data source updates)
   * @return true if the operation succeeded, false otherwise
   */
  private boolean applyToLegacyPersistence(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> sortedChangeSet) {
    // Data will have been sorted by data source updates.
    switch (sortedChangeSet.getType()) {
      case Full:
        applyFullChangeSetToLegacyStore(sortedChangeSet);
        break;
      case Partial:
        return applyPartialChangeSetToLegacyStore(sortedChangeSet);
      case None:
      default:
        break;
    }

    return true;
  }

  /**
   * Applies a full change set to a legacy persistent store.
   */
  private void applyFullChangeSetToLegacyStore(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> sortedChangeSet) {
    // Preserve shouldPersist flag when converting ChangeSet to FullDataSet
    persistentStore.init(new FullDataSet<>(sortedChangeSet.getData(), sortedChangeSet.shouldPersist()));
  }

  /**
   * Applies a partial change set to a legacy persistent store.
   * 
   * @param sortedChangeSet the change set to apply
   * @return true if all operations succeeded, false otherwise
   */
  private boolean applyPartialChangeSetToLegacyStore(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> sortedChangeSet) {
    for (java.util.Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindItemsPair : sortedChangeSet.getData()) {
      for (java.util.Map.Entry<String, ItemDescriptor> item : kindItemsPair.getValue().getItems()) {
        boolean applySuccess = persistentStore.upsert(kindItemsPair.getKey(), item.getKey(), item.getValue());
        if (!applySuccess) {
          return false;
        }
      }
    }

    return true;
  }
}
