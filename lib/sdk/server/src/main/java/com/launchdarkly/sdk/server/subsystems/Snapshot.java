package com.launchdarkly.sdk.server.subsystems;

public interface Snapshot {
  /**
   * Retrieves an item from the specified collection, if available.
   * <p>
   * If the item has been deleted and the store contains a placeholder, it should
   * return that placeholder rather than null.
   *
   * @param kind specifies which collection to use
   * @param key the unique key of the item within that collection
   * @return a versioned item that contains the stored data (or placeholder for deleted data);
   *   null if the key is unknown
   */
  DataStoreTypes.ItemDescriptor get(DataStoreTypes.DataKind kind, String key);

  /**
   * Retrieves all items from the specified collection.
   * <p>
   * If the store contains placeholders for deleted items, it should include them in
   * the results, not filter them out.
   *
   * @param kind specifies which collection to use
   * @return a collection of key-value pairs; the ordering is not significant
   */
  DataStoreTypes.KeyedItems<DataStoreTypes.ItemDescriptor> getAll(DataStoreTypes.DataKind kind);
}