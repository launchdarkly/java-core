package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.concurrent.Future;

/**
 * Internal interface for the data system abstraction.
 * <p>
 * This interface is package-private and should not be used by application code.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 */
interface DataSystem {
  /**
   * Returns the read-only store interface.
   *
   * @return the read-only store
   */
  ReadOnlyStore getStore();

  /**
   * Starts the data system.
   *
   * @return a Future that completes when initialization is complete
   */
  Future<Void> start();

  /**
   * Returns whether the data system has been initialized.
   *
   * @return true if initialized
   */
  boolean isInitialized();

  /**
   * Returns the flag change notifier interface.
   *
   * @return the flag change notifier
   */
  FlagChangeNotifier getFlagChanged();

  /**
   * Returns the data source status provider.
   *
   * @return the data source status provider
   */
  DataSourceStatusProvider getDataSourceStatusProvider();

  /**
   * Returns the data store status provider.
   *
   * @return the data store status provider
   */
  DataStoreStatusProvider getDataStoreStatusProvider();
}

/**
 * Internal interface for read-only access to a data store.
 * <p>
 * This interface is package-private and should not be used by application code.
 */
interface ReadOnlyStore {
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
  ItemDescriptor get(DataKind kind, String key);

  /**
   * Retrieves all items from the specified collection.
   * <p>
   * If the store contains placeholders for deleted items, it should include them in
   * the results, not filter them out.
   *
   * @param kind specifies which collection to use
   * @return a collection of key-value pairs; the ordering is not significant
   */
  KeyedItems<ItemDescriptor> getAll(DataKind kind);

  /**
   * Checks whether this store has been initialized with any data yet.
   *
   * @return true if the store contains data
   */
  boolean isInitialized();
}

/**
 * Internal interface for flag change notifications.
 * <p>
 * This interface is package-private and should not be used by application code.
 */
interface FlagChangeNotifier {
  /**
   * Adds a listener for flag change events.
   *
   * @param listener the listener to add
   */
  void addFlagChangeListener(com.launchdarkly.sdk.server.interfaces.FlagChangeListener listener);

  /**
   * Removes a listener for flag change events.
   *
   * @param listener the listener to remove
   */
  void removeFlagChangeListener(com.launchdarkly.sdk.server.interfaces.FlagChangeListener listener);
}
