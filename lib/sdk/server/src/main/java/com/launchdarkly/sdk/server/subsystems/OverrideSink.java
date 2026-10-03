package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.Map;

/**
 * Receives the contents of the SDK's flag and segment override layer. The SDK implements this
 * interface and passes it to {@link OverrideSource#start(OverrideSink)}. Override sources call
 * it. They do not implement it.
 * <p>
 * Flag overrides are currently experimental and subject to change.
 *
 * @since 7.18.0
 */
public interface OverrideSink {
  /**
   * Replaces the entire override layer with the given flag and segment entries. An empty
   * collection clears the layer. Each call is a full snapshot: entries absent from the call are
   * removed from the layer.
   * <p>
   * The data uses the SDK's standard data kinds, {@link com.launchdarkly.sdk.server.DataModel#FEATURES}
   * and {@link com.launchdarkly.sdk.server.DataModel#SEGMENTS}, with item descriptors whose items
   * are the SDK's flag and segment model objects, as produced by
   * {@link DataKind#deserialize(String)}. Sources supply ordinary, fully parsed entities. The SDK
   * itself marks the entries as overrides. The source's objects are never modified.
   * <p>
   * This method is safe to call from any thread. The SDK serializes calls, and the new layer
   * contents are visible to evaluations when the call returns.
   *
   * @param data the complete set of override entries, grouped by data kind
   */
  void setOverrides(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data);
}
