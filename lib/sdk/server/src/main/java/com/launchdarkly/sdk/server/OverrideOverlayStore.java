package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Merges an {@link OverrideLayer} over a base store. A read for a key returns the override entry
 * when one exists, and the base entry otherwise. The overlay sits at the store read boundary, so
 * targeting rules, prerequisites, and segment matches behave identically for overridden and
 * ordinary data: they are the same reads through the same boundary.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class OverrideOverlayStore implements ReadOnlyStore {
  private final ReadOnlyStore base;
  private final OverrideLayer layer;

  OverrideOverlayStore(ReadOnlyStore base, OverrideLayer layer) {
    this.base = base;
    this.layer = layer;
  }

  /**
   * Returns the override entry for the key if one exists, and otherwise delegates to the base
   * store. This works even when the base store is uninitialized, because an uninitialized base
   * reports not found rather than failing.
   */
  @Override
  public ItemDescriptor get(DataKind kind, String key) {
    ItemDescriptor override = layer.get(kind, key);
    if (override != null) {
      return override;
    }
    return base.get(kind, key);
  }

  /**
   * Returns the union of the base store's items and the layer's items. The override entry wins for
   * any key present in both, including keys that the base holds as deleted-item placeholders.
   * <p>
   * When the base store fails and the layer holds entries, the result is the layer's entries alone.
   * A per-key read serves those entries whatever the state of the base, so an all-flags read does
   * the same. When the layer is empty, the base failure propagates.
   */
  @Override
  public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
    ImmutableMap<String, ItemDescriptor> overrides = layer.all(kind);
    Iterable<Map.Entry<String, ItemDescriptor>> baseItems;
    try {
      KeyedItems<ItemDescriptor> fromBase = base.getAll(kind);
      if (overrides.isEmpty()) {
        return fromBase;
      }
      baseItems = fromBase == null || fromBase.getItems() == null ? Collections.emptyList() : fromBase.getItems();
    } catch (RuntimeException e) {
      if (overrides.isEmpty()) {
        throw e;
      }
      baseItems = Collections.emptyList();
    }

    ImmutableList.Builder<Map.Entry<String, ItemDescriptor>> result = ImmutableList.builder();
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, ItemDescriptor> item : baseItems) {
      ItemDescriptor override = overrides.get(item.getKey());
      result.add(override == null ? item : new AbstractMap.SimpleEntry<>(item.getKey(), override));
      seen.add(item.getKey());
    }
    for (Map.Entry<String, ItemDescriptor> override : overrides.entrySet()) {
      if (!seen.contains(override.getKey())) {
        result.add(override);
      }
    }
    return new KeyedItems<>(result.build());
  }

  /**
   * Delegates to the base store. The override layer never affects initialization status or data
   * availability.
   */
  @Override
  public boolean isInitialized() {
    return base.isInitialized();
  }
}
