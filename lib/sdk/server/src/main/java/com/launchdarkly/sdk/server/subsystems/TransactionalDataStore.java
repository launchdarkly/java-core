package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;

/**
 * Interface for a data store that holds feature flags and related data received by the SDK.
 * This interface supports updating the store transactionally using ChangeSets.
 * <p>
 * Ordinarily, the only implementation of this interface is the default in-memory
 * implementation, which holds references to actual SDK data model objects. Any data store
 * implementation that uses an external store, such as a database, should instead use
 * {@link PersistentDataStore}.
 * <p>
 * Implementations must be thread-safe.
 * <p>
 * This interface is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * 
 * @see PersistentDataStore
 */
public interface TransactionalDataStore {
  /**
   * Apply the given change set to the store. This should be done atomically if possible.
   * 
   * @param changeSet the changeset to apply
   */
  void apply(ChangeSet<ItemDescriptor> changeSet);
  
  /**
   * Returns the selector for the currently stored data. The selector will be non-null but may be empty.
   * 
   * @return the selector for the currently stored data
   */
  Selector getSelector();
}
