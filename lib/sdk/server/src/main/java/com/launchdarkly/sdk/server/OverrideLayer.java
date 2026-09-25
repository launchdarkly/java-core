package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.HashMap;
import java.util.Map;

/**
 * The override layer: a thread-safe store of override entries that an override source replaces
 * wholesale on each update. Entries take precedence over LaunchDarkly data at the store read
 * boundary; see {@link OverrideOverlayStore}.
 * <p>
 * Each entry is a marked shallow copy of the entity that the source supplied. The copy shares its
 * nested collections with the source's entity, and the layer never writes to them. The source's
 * entity is never marked, so a source may retain the entities it supplied and supply them again.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class OverrideLayer {
  /**
   * The previous and current contents of the layer after a replacement. The maps must not be
   * modified.
   */
  static final class Replacement {
    final ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> previous;
    final ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> current;

    Replacement(
        ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> previous,
        ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> current
    ) {
      this.previous = previous;
      this.current = current;
    }
  }

  // The contents are an immutable map that is swapped on each update, so the layer holds exactly
  // one snapshot at any instant and readers never take a lock.
  private volatile ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> contents = ImmutableMap.of();
  // A single volatile read decides the common case of a configured but unpopulated layer.
  private volatile boolean nonEmpty = false;
  private final Object writeLock = new Object();

  /**
   * Atomically replaces the entire layer contents. A null or empty collection clears the layer.
   *
   * @param data the complete set of entries, grouped by data kind
   * @return the previous and the new contents
   */
  Replacement setAll(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    Map<DataKind, Map<String, ItemDescriptor>> collected = new HashMap<>();
    int count = 0;
    if (data != null) {
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : data) {
        Map<String, ItemDescriptor> items = collected.computeIfAbsent(kindEntry.getKey(), k -> new HashMap<>());
        Iterable<Map.Entry<String, ItemDescriptor>> kindItems = kindEntry.getValue() == null
            ? null : kindEntry.getValue().getItems();
        if (kindItems == null) {
          continue;
        }
        for (Map.Entry<String, ItemDescriptor> item : kindItems) {
          items.put(item.getKey(), markedCopy(item.getValue()));
          count++;
        }
      }
    }
    ImmutableMap.Builder<DataKind, ImmutableMap<String, ItemDescriptor>> builder = ImmutableMap.builder();
    for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : collected.entrySet()) {
      builder.put(e.getKey(), ImmutableMap.copyOf(e.getValue()));
    }
    ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> replacement = builder.build();

    synchronized (writeLock) {
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> previous = contents;
      contents = replacement;
      nonEmpty = count != 0;
      return new Replacement(previous, replacement);
    }
  }

  /**
   * Returns the override entry for a key, or null if the layer has none.
   *
   * @param kind the data kind
   * @param key the item key
   * @return the marked entry or null
   */
  ItemDescriptor get(DataKind kind, String key) {
    if (!nonEmpty) {
      return null;
    }
    ImmutableMap<String, ItemDescriptor> items = contents.get(kind);
    return items == null ? null : items.get(key);
  }

  /**
   * Returns the entries of a kind. The map must not be modified.
   *
   * @param kind the data kind
   * @return the entries, possibly empty
   */
  ImmutableMap<String, ItemDescriptor> all(DataKind kind) {
    ImmutableMap<String, ItemDescriptor> items = contents.get(kind);
    return items == null ? ImmutableMap.of() : items;
  }

  /**
   * Reports whether the layer contains no entries.
   *
   * @return true if empty
   */
  boolean isEmpty() {
    return !nonEmpty;
  }

  // Returns a copy of the descriptor whose item carries the override marker. An item of another
  // type, or a deleted item placeholder, is returned as is.
  static ItemDescriptor markedCopy(ItemDescriptor item) {
    Object entity = item.getItem();
    if (entity instanceof FeatureFlag) {
      return new ItemDescriptor(item.getVersion(), ((FeatureFlag) entity).markedAsOverride());
    }
    if (entity instanceof Segment) {
      return new ItemDescriptor(item.getVersion(), ((Segment) entity).markedAsOverride());
    }
    return item;
  }
}
