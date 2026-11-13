package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The override layer: a thread-safe store of override entries that an override source replaces
 * wholesale on each update. Entries take precedence over LaunchDarkly data at the store read
 * boundary; see {@link OverrideOverlayStore}.
 * <p>
 * Each entry is a marked shallow copy of the entity that the source supplied. The copy shares its
 * nested collections with the source's entity, and the layer never writes to them. The source's
 * entity is never marked, so a source may retain the entities it supplied and supply them again.
 * <p>
 * The layer never holds a deleted item. Serving one would hide the LaunchDarkly data for its key,
 * but an override only adds or replaces definitions, so a replacement that contains one is rejected.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class OverrideLayer implements DataModelDependencies.ExternalDependencies {
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
  // The dependency edges of the layer's own entries: which entries reference which flags and
  // segments. Rebuilt with the contents on every replacement and swapped with them.
  private volatile DataModelDependencies.DependencyTracker dependencies = new DataModelDependencies.DependencyTracker();

  /**
   * Thrown by {@link OverrideLayer#setAll(Iterable)} when the data contains an item that the layer
   * cannot hold. The data is rejected as a whole and the layer is unchanged.
   */
  static class RejectedItemException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    RejectedItemException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when the data contains a deleted item. The layer never holds one.
   */
  static final class DeletedItemException extends RejectedItemException {
    private static final long serialVersionUID = 1L;

    DeletedItemException(DataKind kind, String key) {
      super(describeKind(kind) + " \"" + key + "\" is marked as deleted");
    }
  }

  /**
   * Thrown when the data lists a flag or segment under a key other than the definition's own key.
   * Evaluation reads an entry by the key it is listed under, while the all flags state and events
   * report the definition's key, so such an entry would be served under one key and reported under
   * the other.
   */
  static final class MismatchedKeyException extends RejectedItemException {
    private static final long serialVersionUID = 1L;

    MismatchedKeyException(DataKind kind, String key, String definitionKey) {
      super(describeKind(kind) + " \"" + key + "\" has the key "
          + (definitionKey == null ? "null" : "\"" + definitionKey + "\"")
          + "; the key of each definition must be its key in the snapshot");
    }
  }

  private static String describeKind(DataKind kind) {
    if (kind == DataModel.FEATURES) {
      return "flag";
    }
    if (kind == DataModel.SEGMENTS) {
      return "segment";
    }
    return "item of kind " + (kind == null ? "null" : kind.getName());
  }

  /**
   * Atomically replaces the entire layer contents. A null or empty collection clears the layer.
   *
   * @param data the complete set of entries, grouped by data kind
   * @return the previous and the new contents
   * @throws RejectedItemException if the data contains a deleted item, or a flag or segment listed
   *     under a key other than its own; the layer is unchanged
   */
  Replacement setAll(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    Map<DataKind, Map<String, ItemDescriptor>> collected = new HashMap<>();
    DataModelDependencies.DependencyTracker tracker = new DataModelDependencies.DependencyTracker();
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
          // Nothing is swapped in until every entry is collected, so a rejection leaves the layer as
          // it was.
          if (isDeleted(item.getValue())) {
            throw new DeletedItemException(kindEntry.getKey(), item.getKey());
          }
          Object entity = item.getValue() == null ? null : item.getValue().getItem();
          if (entity instanceof DataModel.VersionedData
              && !Objects.equals(((DataModel.VersionedData) entity).getKey(), item.getKey())) {
            throw new MismatchedKeyException(kindEntry.getKey(), item.getKey(),
                ((DataModel.VersionedData) entity).getKey());
          }
          ItemDescriptor copied = markedCopy(item.getValue());
          items.put(item.getKey(), copied);
          tracker.updateDependenciesFrom(kindEntry.getKey(), item.getKey(), copied);
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
      dependencies = tracker;
      nonEmpty = count != 0;
      return new Replacement(previous, replacement);
    }
  }

  /**
   * Adds the given item to the set together with every item whose override entry depends on it,
   * directly or through other override entries. A LaunchDarkly update uses this so that a change to
   * a LaunchDarkly segment or prerequisite notifies the overridden flags that reference it, which
   * the data system's own dependency tracking cannot see.
   */
  @Override
  public void addAffectedItems(Set<DataModelDependencies.KindAndKey> itemsOut, DataModelDependencies.KindAndKey item) {
    dependencies.addAffectedItems(itemsOut, item);
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

  // Reports whether a descriptor is a deleted item: the placeholder with no item, which
  // DataKind.deserialize produces for a definition marked as deleted, or a flag or segment that is
  // marked as deleted.
  private static boolean isDeleted(ItemDescriptor item) {
    if (item == null) {
      return false;
    }
    Object entity = item.getItem();
    return entity == null
        || (entity instanceof DataModel.VersionedData && ((DataModel.VersionedData) entity).isDeleted());
  }

  // Returns a copy of the descriptor whose item carries the override marker. An item of another
  // type is returned as is.
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
