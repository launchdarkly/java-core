package com.launchdarkly.sdk.server;

/**
 * Optional interface for data stores that can disable their internal cache.
 * <p>
 * This is currently for internal implementations only.
 */
interface DisableableCache {
  /**
   * Disables the internal cache. After this call, the cache is no longer
   * consulted on reads and no longer populated by writes.
   * <p>
   * Implementations should release the cache contents so the memory can be
   * reclaimed. The call must be idempotent: subsequent invocations should be
   * safe and have no further effect.
   */
  void disableCache();
}
