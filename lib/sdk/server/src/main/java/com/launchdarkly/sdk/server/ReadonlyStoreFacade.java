package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

/**
 * Internal facade that wraps a DataStore to provide read-only access.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class ReadonlyStoreFacade implements ReadOnlyStore {
  private final DataStore store;

  ReadonlyStoreFacade(DataStore store) {
    this.store = store;
  }

  @Override
  public ItemDescriptor get(DataKind kind, String key) {
    return store.get(kind, key);
  }

  @Override
  public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
    return store.getAll(kind);
  }

  @Override
  public boolean isInitialized() {
    return store.isInitialized();
  }
}

