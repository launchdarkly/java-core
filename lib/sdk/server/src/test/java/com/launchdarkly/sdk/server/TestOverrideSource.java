package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.OverrideSink;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A test override source. It supplies its initial data when started and forwards later
 * replacements to the sink, as if the source's backing data had changed.
 */
@SuppressWarnings("javadoc")
public final class TestOverrideSource implements OverrideSource, ComponentConfigurer<OverrideSource> {
  private final Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> initialData;
  private volatile OverrideSink sink;
  final AtomicBoolean started = new AtomicBoolean(false);
  final AtomicBoolean closed = new AtomicBoolean(false);
  volatile ClientContext buildContext;
  // When set, start throws it instead of supplying data.
  volatile RuntimeException startError;
  // When set with startError, start supplies the data to the sink and then throws.
  volatile boolean supplyDataBeforeStartError;
  // When set, close throws it after recording the close.
  volatile RuntimeException closeError;

  public TestOverrideSource(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> initialData) {
    this.initialData = initialData;
  }

  @Override
  public OverrideSource build(ClientContext clientContext) {
    this.buildContext = clientContext;
    return this;
  }

  @Override
  public void start(OverrideSink sink) {
    if (startError != null) {
      if (supplyDataBeforeStartError && initialData != null) {
        sink.setOverrides(initialData);
      }
      throw startError;
    }
    this.sink = sink;
    started.set(true);
    if (initialData != null) {
      sink.setOverrides(initialData);
    }
  }

  /**
   * Replaces the override layer contents. Must be called after the source was started.
   *
   * @param data the complete set of override entries
   */
  public void setOverrides(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    OverrideSink s = sink;
    if (s != null && !closed.get()) {
      s.setOverrides(data);
    }
  }

  /**
   * Supplies a snapshot to the sink even after the source was closed, as a misbehaving source or a
   * load that races the close could.
   *
   * @param data the complete set of override entries
   */
  public void deliverEvenIfClosed(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    sink.setOverrides(data);
  }

  @Override
  public void close() {
    closed.set(true);
    if (closeError != null) {
      throw closeError;
    }
  }
}
