package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.DataStoreTestTypes.toItemsMap;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.OverrideLayerTest.flagsOnly;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class OverrideOverlayStoreTest {
  /**
   * A base store with arbitrary data, initialization state, and an optional read failure.
   */
  static final class FakeBaseStore implements ReadOnlyStore {
    final Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    boolean initialized = true;
    RuntimeException getAllError = null;
    int getAllCalls = 0;

    FakeBaseStore withFlag(FeatureFlag flag) {
      data.computeIfAbsent(FEATURES, k -> new HashMap<>()).put(flag.getKey(), new ItemDescriptor(flag.getVersion(), flag));
      return this;
    }

    FakeBaseStore withDeletedFlag(String key, int version) {
      data.computeIfAbsent(FEATURES, k -> new HashMap<>()).put(key, ItemDescriptor.deletedItem(version));
      return this;
    }

    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      Map<String, ItemDescriptor> items = data.get(kind);
      return items == null ? null : items.get(key);
    }

    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      getAllCalls++;
      if (getAllError != null) {
        throw getAllError;
      }
      Map<String, ItemDescriptor> items = data.get(kind);
      return new KeyedItems<>(items == null ? null : items.entrySet());
    }

    @Override
    public boolean isInitialized() {
      return initialized;
    }
  }

  private static FeatureFlag flagOf(ItemDescriptor item) {
    return (FeatureFlag) item.getItem();
  }

  @Test
  public void getPrefersOverrideEntry() {
    FeatureFlag ldFlag = flagBuilder("shared").version(1).on(true).build();
    FeatureFlag ldOnly = flagBuilder("ld-only").version(1).build();
    FeatureFlag overrideFlag = flagBuilder("shared").version(2).on(false).build();
    FeatureFlag overrideOnly = flagBuilder("override-only").version(1).build();
    FakeBaseStore base = new FakeBaseStore().withFlag(ldFlag).withFlag(ldOnly);
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(overrideFlag, overrideOnly));
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, layer);

    ItemDescriptor shared = overlay.get(FEATURES, "shared");
    assertEquals(2, shared.getVersion());
    assertTrue(flagOf(shared).isOverride());
    assertFalse(flagOf(shared).isOn());

    ItemDescriptor onlyLd = overlay.get(FEATURES, "ld-only");
    assertFalse(flagOf(onlyLd).isOverride());
    assertSame(ldOnly, onlyLd.getItem());

    assertTrue(flagOf(overlay.get(FEATURES, "override-only")).isOverride());
    assertNull(overlay.get(FEATURES, "missing"));
    assertNull(overlay.get(SEGMENTS, "shared"));
  }

  @Test
  public void getServesOverridesFromUninitializedBase() {
    FakeBaseStore base = new FakeBaseStore();
    base.initialized = false;
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(flagBuilder("flag").build()));
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, layer);

    assertFalse(overlay.isInitialized());
    assertTrue(flagOf(overlay.get(FEATURES, "flag")).isOverride());
    assertNull(overlay.get(FEATURES, "other"));
  }

  @Test
  public void isInitializedDelegatesToBase() {
    FakeBaseStore base = new FakeBaseStore();
    OverrideLayer layer = new OverrideLayer();
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, layer);
    assertTrue(overlay.isInitialized());
    base.initialized = false;
    assertFalse(overlay.isInitialized());
    layer.setAll(flagsOnly(flagBuilder("flag").build()));
    assertFalse(overlay.isInitialized());
  }

  @Test
  public void getAllIsUnionWithOverridePrecedence() {
    FakeBaseStore base = new FakeBaseStore()
        .withFlag(flagBuilder("shared").version(1).build())
        .withFlag(flagBuilder("ld-only").version(1).build())
        .withDeletedFlag("tombstoned", 9);
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(
        flagBuilder("shared").version(2).build(),
        flagBuilder("override-only").version(1).build(),
        flagBuilder("tombstoned").version(1).build()));
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, layer);

    Map<String, ItemDescriptor> all = toItemsMap(overlay.getAll(FEATURES));
    assertEquals(4, all.size());
    assertEquals(2, all.get("shared").getVersion());
    assertTrue(flagOf(all.get("shared")).isOverride());
    assertFalse(flagOf(all.get("ld-only")).isOverride());
    assertTrue(flagOf(all.get("override-only")).isOverride());
    // The override wins over a deleted-item placeholder in the base.
    assertTrue(flagOf(all.get("tombstoned")).isOverride());
  }

  @Test
  public void getAllWithEmptyLayerReturnsBaseResultAsIs() {
    FakeBaseStore base = new FakeBaseStore().withFlag(flagBuilder("a").build());
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, new OverrideLayer());
    KeyedItems<ItemDescriptor> all = overlay.getAll(FEATURES);
    assertEquals(1, toItemsMap(all).size());
    assertFalse(flagOf(toItemsMap(all).get("a")).isOverride());
  }

  @Test
  public void getAllServesOverridesWhenBaseFails() {
    FakeBaseStore base = new FakeBaseStore();
    base.getAllError = new RuntimeException("store down");
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(flagBuilder("flag").build()));
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, layer);

    Map<String, ItemDescriptor> all = toItemsMap(overlay.getAll(FEATURES));
    assertEquals(1, all.size());
    assertTrue(flagOf(all.get("flag")).isOverride());
  }

  @Test
  public void getAllPropagatesBaseFailureWhenLayerIsEmpty() {
    FakeBaseStore base = new FakeBaseStore();
    RuntimeException error = new RuntimeException("store down");
    base.getAllError = error;
    OverrideOverlayStore overlay = new OverrideOverlayStore(base, new OverrideLayer());
    try {
      overlay.getAll(FEATURES);
      fail("expected exception");
    } catch (RuntimeException e) {
      assertSame(error, e);
    }
  }
}
