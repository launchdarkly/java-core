package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModelDependencies.KindAndKey;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.ruleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.clauseMatchingSegment;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class OverrideLayerTest {
  static Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> collections(
      Iterable<FeatureFlag> flags, Iterable<Segment> segments) {
    List<Map.Entry<String, ItemDescriptor>> flagItems = new ArrayList<>();
    for (FeatureFlag f : flags) {
      flagItems.add(new AbstractMap.SimpleEntry<>(f.getKey(), new ItemDescriptor(f.getVersion(), f)));
    }
    List<Map.Entry<String, ItemDescriptor>> segmentItems = new ArrayList<>();
    for (Segment s : segments) {
      segmentItems.add(new AbstractMap.SimpleEntry<>(s.getKey(), new ItemDescriptor(s.getVersion(), s)));
    }
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> result = new ArrayList<>();
    result.add(new AbstractMap.SimpleEntry<>(FEATURES, new KeyedItems<>(flagItems)));
    result.add(new AbstractMap.SimpleEntry<>(SEGMENTS, new KeyedItems<>(segmentItems)));
    return result;
  }

  static Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> flagsOnly(FeatureFlag... flags) {
    return collections(java.util.Arrays.asList(flags), Collections.<Segment>emptyList());
  }

  @Test
  public void newLayerIsEmpty() {
    OverrideLayer layer = new OverrideLayer();
    assertTrue(layer.isEmpty());
    assertNull(layer.get(FEATURES, "flag"));
    assertTrue(layer.all(FEATURES).isEmpty());
  }

  @Test
  public void setAllStoresMarkedCopiesWithoutMutatingSource() {
    OverrideLayer layer = new OverrideLayer();
    FeatureFlag flag = flagBuilder("flag1").version(2).build();
    Segment segment = segmentBuilder("segment1").version(3).build();

    layer.setAll(collections(Collections.singletonList(flag), Collections.singletonList(segment)));

    assertFalse(flag.isOverride());
    assertFalse(segment.isOverride());
    assertFalse(layer.isEmpty());

    ItemDescriptor storedFlag = layer.get(FEATURES, "flag1");
    assertNotNull(storedFlag);
    assertEquals(2, storedFlag.getVersion());
    assertTrue(((FeatureFlag) storedFlag.getItem()).isOverride());
    assertNotSame(flag, storedFlag.getItem());
    assertSame(flag.preprocessed, ((FeatureFlag) storedFlag.getItem()).preprocessed);

    ItemDescriptor storedSegment = layer.get(SEGMENTS, "segment1");
    assertNotNull(storedSegment);
    assertEquals(3, storedSegment.getVersion());
    assertTrue(((Segment) storedSegment.getItem()).isOverride());
  }

  @Test
  public void setAllReplacesEverything() {
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(flagBuilder("a").build(), flagBuilder("b").build()));
    assertNotNull(layer.get(FEATURES, "a"));
    assertNotNull(layer.get(FEATURES, "b"));

    OverrideLayer.Replacement replacement = layer.setAll(flagsOnly(flagBuilder("b").build(), flagBuilder("c").build()));
    assertNull(layer.get(FEATURES, "a"));
    assertNotNull(layer.get(FEATURES, "b"));
    assertNotNull(layer.get(FEATURES, "c"));
    assertEquals(2, replacement.previous.get(FEATURES).size());
    assertEquals(2, replacement.current.get(FEATURES).size());
    assertTrue(replacement.previous.get(FEATURES).containsKey("a"));
    assertTrue(replacement.current.get(FEATURES).containsKey("c"));
  }

  @Test
  public void emptyOrNullSnapshotClearsLayer() {
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(flagBuilder("a").build()));
    assertFalse(layer.isEmpty());

    layer.setAll(Collections.emptyList());
    assertTrue(layer.isEmpty());
    assertNull(layer.get(FEATURES, "a"));

    layer.setAll(flagsOnly(flagBuilder("a").build()));
    layer.setAll(null);
    assertTrue(layer.isEmpty());
  }

  @Test
  public void deletedPlaceholderIsStoredAsIs() {
    OverrideLayer layer = new OverrideLayer();
    ItemDescriptor deleted = ItemDescriptor.deletedItem(5);
    layer.setAll(Collections.singletonList(new AbstractMap.SimpleEntry<>(FEATURES,
        new KeyedItems<>(Collections.singletonList(new AbstractMap.SimpleEntry<>("gone", deleted))))));
    assertSame(deleted, layer.get(FEATURES, "gone"));
  }

  @Test
  public void allReturnsEntriesOfKindOnly() {
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(collections(Collections.singletonList(flagBuilder("f").build()),
        Collections.singletonList(segmentBuilder("s").build())));
    assertEquals(1, layer.all(FEATURES).size());
    assertEquals(1, layer.all(SEGMENTS).size());
    assertTrue(layer.all(FEATURES).containsKey("f"));
    assertTrue(layer.all(SEGMENTS).containsKey("s"));
  }

  private static Set<KindAndKey> affectedFrom(OverrideLayer layer, DataKind kind, String key) {
    Set<KindAndKey> affected = new HashSet<>();
    layer.addAffectedItems(affected, new KindAndKey(kind, key));
    return affected;
  }

  private static Set<KindAndKey> items(KindAndKey... items) {
    return new HashSet<>(java.util.Arrays.asList(items));
  }

  @Test
  public void reportsEntriesThatDependOnAnItem() {
    // a matches a segment; b has a as a prerequisite, so b depends on the segment through a.
    FeatureFlag a = flagBuilder("a").version(1).on(true)
        .rules(ruleBuilder().id("r").variation(0).clauses(clauseMatchingSegment("seg")).build()).build();
    FeatureFlag b = flagBuilder("b").version(1).on(true).prerequisites(prerequisite("a", 0)).build();
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(a, b));

    assertEquals(items(new KindAndKey(SEGMENTS, "seg"), new KindAndKey(FEATURES, "a"), new KindAndKey(FEATURES, "b")),
        affectedFrom(layer, SEGMENTS, "seg"));
    assertEquals(items(new KindAndKey(FEATURES, "a"), new KindAndKey(FEATURES, "b")),
        affectedFrom(layer, FEATURES, "a"));
    assertEquals(items(new KindAndKey(SEGMENTS, "unreferenced")), affectedFrom(layer, SEGMENTS, "unreferenced"));
  }

  @Test
  public void forgetsEdgesOfReplacedEntries() {
    FeatureFlag a = flagBuilder("a").version(1).on(true)
        .rules(ruleBuilder().id("r").variation(0).clauses(clauseMatchingSegment("seg")).build()).build();
    FeatureFlag c = flagBuilder("c").version(1).on(true)
        .rules(ruleBuilder().id("r").variation(0).clauses(clauseMatchingSegment("seg2")).build()).build();
    OverrideLayer layer = new OverrideLayer();
    layer.setAll(flagsOnly(a));
    layer.setAll(flagsOnly(c));

    assertEquals(items(new KindAndKey(SEGMENTS, "seg")), affectedFrom(layer, SEGMENTS, "seg"));
    assertEquals(items(new KindAndKey(SEGMENTS, "seg2"), new KindAndKey(FEATURES, "c")), affectedFrom(layer, SEGMENTS, "seg2"));
  }
}
