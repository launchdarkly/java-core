package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;

/**
 * Optional interface for data stores that can export their entire contents.
 * <p>
 * This interface is used to enable recovery scenarios where a persistent store
 * needs to be re-synchronized from an in-memory cache. Not all data stores need
 * to implement this interface.
 * <p>
 * This is currently only for internal implementations.
 */
interface CacheExporter {
  /**
   * Exports all data from the cache across all known DataKinds.
   * 
   * @return A FullDataSet containing all items in the cache. The data is a snapshot
   *   taken at the time of the call and may be stale immediately after return.
   */
  FullDataSet<ItemDescriptor> exportAll();

  /**
   * Indicates if the cache has been populated with a full data set.
   * 
   * @return true when the cache has been populated
   */
  boolean isInitialized();
}

