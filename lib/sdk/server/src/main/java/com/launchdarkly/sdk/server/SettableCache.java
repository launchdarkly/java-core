package com.launchdarkly.sdk.server;

/**
 * Optional interface for data stores that can accept a cache exporter
 * for recovery synchronization.
 * <p>
 * This interface is used in write-through architectures where a persistent store
 * may fail temporarily. When the persistent store recovers, it can sync data from
 * an external authoritative source (like an in-memory store) rather than relying
 * solely on its internal cache.
 * <p>
 * In the long-term, internal caching should be removed from store implementations and managed centrally.
 * <p>
 * This is currently for internal implementations only.
 */
interface SettableCache {
  /**
   * Sets an external cache exporter for recovery synchronization.
   * <p>
   * This should be called during initialization if the data store is being used
   * in a write-through architecture where an external store maintains authoritative data.
   * When the persistent store recovers from an outage, it will export data from this
   * external source and write it to the underlying persistent storage.
   * 
   * @param externalDataSource an external cache to sync from during recovery
   */
  void setCacheExporter(CacheExporter externalDataSource);
}
