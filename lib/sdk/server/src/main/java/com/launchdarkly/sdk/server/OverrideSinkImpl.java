package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.DataModelDependencies.DependencyTracker;
import com.launchdarkly.sdk.server.DataModelDependencies.KindAndKey;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.OverrideSink;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;

/**
 * Applies override layer replacements supplied by an override source, and notifies flag change
 * listeners of the flags affected by each replacement.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class OverrideSinkImpl implements OverrideSink {
  private static final DataKind[] DIFF_KINDS = new DataKind[] { FEATURES, SEGMENTS };

  private final OverrideLayer layer;
  private final ReadOnlyStore base;
  private final EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster;
  private final LDLogger logger;

  /**
   * Creates a sink that writes to the given layer.
   *
   * @param layer the override layer
   * @param base the raw store that holds LaunchDarkly data, without the overlay. Merged-view
   *   snapshots for change computation are built from it plus the layer.
   * @param flagChangeBroadcaster the client's flag change broadcaster
   * @param logger the logger
   */
  OverrideSinkImpl(
      OverrideLayer layer,
      ReadOnlyStore base,
      EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster,
      LDLogger logger
  ) {
    this.layer = layer;
    this.base = base;
    this.flagChangeBroadcaster = flagChangeBroadcaster;
    this.logger = logger;
  }

  /**
   * Atomically replaces the entire override layer, then notifies listeners of every flag whose
   * merged-view evaluation may have changed. Calls are serialized, so overlapping updates from a
   * source cannot interleave.
   */
  @Override
  public synchronized void setOverrides(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    // Computing affected flags requires snapshots of the merged view before and after the
    // replacement. Skip all of that work when nothing is listening.
    if (!flagChangeBroadcaster.hasListeners()) {
      layer.setAll(data);
      return;
    }

    OverrideLayer.Replacement replacement = layer.setAll(data);
    Map<DataKind, Map<String, ItemDescriptor>> oldMerged = snapshotMergedView(replacement.previous);
    Map<DataKind, Map<String, ItemDescriptor>> newMerged = snapshotMergedView(replacement.current);

    Set<String> affected = computeAffectedFlags(replacement.previous, replacement.current, oldMerged, newMerged);
    if (!affected.isEmpty()) {
      logger.debug("Override update affected {} flag(s)", affected.size());
    }
    for (String key : affected) {
      flagChangeBroadcaster.broadcast(new FlagChangeEvent(key));
    }
  }

  // Returns the keys of all flags whose merged-view evaluation may have changed when the layer was
  // replaced. The result includes the flags whose override entries were added, removed, or changed.
  // Dependency fan-out adds every flag that depends, directly or transitively, on any added,
  // removed, or changed entry of either kind.
  static Set<String> computeAffectedFlags(
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> oldOverrides,
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> newOverrides,
      Map<DataKind, Map<String, ItemDescriptor>> oldMerged,
      Map<DataKind, Map<String, ItemDescriptor>> newMerged
  ) {
    Set<KindAndKey> seeds = diffOverrides(oldOverrides, newOverrides);
    Set<String> flagKeys = new LinkedHashSet<>();
    if (seeds.isEmpty()) {
      return flagKeys;
    }

    // Dependency edges are computed over both the old and the new merged views, because a
    // replacement can rewire dependencies. For example, removing a flag override restores the
    // prerequisite edges of the LaunchDarkly definition. Flags that depended on the override's
    // references exist as dependents only in the old view.
    DependencyTracker oldTracker = trackerFromView(oldMerged);
    DependencyTracker newTracker = trackerFromView(newMerged);
    Set<KindAndKey> affected = new LinkedHashSet<>();
    for (KindAndKey seed : seeds) {
      oldTracker.addAffectedItems(affected, seed);
      newTracker.addAffectedItems(affected, seed);
    }
    for (KindAndKey item : affected) {
      if (item.kind == FEATURES) {
        flagKeys.add(item.key);
      }
    }
    return flagKeys;
  }

  // Returns each key whose override entry differs between the two layer snapshots. An added or
  // removed entry is always a change, even when its content matches the underlying LaunchDarkly
  // data: the override marker alone changes the served entry. Entries present in both snapshots
  // are compared by version and serialized form, because the layer is rebuilt wholesale on every
  // update and identity comparison would report every retained entry as changed.
  private static Set<KindAndKey> diffOverrides(
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> oldOverrides,
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> newOverrides
  ) {
    Set<KindAndKey> seeds = new LinkedHashSet<>();
    for (DataKind kind : DIFF_KINDS) {
      Map<String, ItemDescriptor> oldItems = oldOverrides.getOrDefault(kind, ImmutableMap.of());
      Map<String, ItemDescriptor> newItems = newOverrides.getOrDefault(kind, ImmutableMap.of());
      for (Map.Entry<String, ItemDescriptor> e : oldItems.entrySet()) {
        ItemDescriptor newItem = newItems.get(e.getKey());
        if (newItem == null || !itemsEqual(kind, e.getValue(), newItem)) {
          seeds.add(new KindAndKey(kind, e.getKey()));
        }
      }
      for (String key : newItems.keySet()) {
        if (!oldItems.containsKey(key)) {
          seeds.add(new KindAndKey(kind, key));
        }
      }
    }
    return seeds;
  }

  private static boolean itemsEqual(DataKind kind, ItemDescriptor a, ItemDescriptor b) {
    if (a.getVersion() != b.getVersion()) {
      return false;
    }
    return Objects.equals(kind.serialize(a), kind.serialize(b));
  }

  // Captures the data visible at the store read boundary: base data with the given override entries
  // overlaid. A base read failure for a kind yields just the overrides for that kind. This degrades
  // the dependency fan-out but never loses the directly changed keys.
  private Map<DataKind, Map<String, ItemDescriptor>> snapshotMergedView(
      ImmutableMap<DataKind, ImmutableMap<String, ItemDescriptor>> overrides
  ) {
    Map<DataKind, Map<String, ItemDescriptor>> view = new HashMap<>();
    for (DataKind kind : DIFF_KINDS) {
      Map<String, ItemDescriptor> items = new HashMap<>();
      try {
        KeyedItems<ItemDescriptor> baseItems = base.getAll(kind);
        if (baseItems != null && baseItems.getItems() != null) {
          for (Map.Entry<String, ItemDescriptor> item : baseItems.getItems()) {
            items.put(item.getKey(), item.getValue());
          }
        }
      } catch (RuntimeException e) {
        logger.debug("Unable to read {} from the data store while computing override changes: {}",
            kind.getName(), e.toString());
      }
      items.putAll(overrides.getOrDefault(kind, ImmutableMap.of()));
      view.put(kind, items);
    }
    return view;
  }

  private static DependencyTracker trackerFromView(Map<DataKind, Map<String, ItemDescriptor>> view) {
    DependencyTracker tracker = new DependencyTracker();
    for (DataKind kind : DIFF_KINDS) {
      Map<String, ItemDescriptor> items = view.get(kind);
      if (items == null) {
        continue;
      }
      for (Map.Entry<String, ItemDescriptor> e : items.entrySet()) {
        tracker.updateDependenciesFrom(kind, e.getKey(), e.getValue());
      }
    }
    return tracker;
  }
}
