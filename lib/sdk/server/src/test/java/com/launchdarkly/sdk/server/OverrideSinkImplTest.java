package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.OverrideOverlayStoreTest.FakeBaseStore;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.ModelBuilders.clauseMatchingSegment;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.ruleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static com.launchdarkly.sdk.server.OverrideLayerTest.collections;
import static com.launchdarkly.sdk.server.OverrideLayerTest.flagsOnly;
import static com.launchdarkly.sdk.server.TestComponents.nullLogger;
import static com.launchdarkly.sdk.server.TestComponents.sharedExecutor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class OverrideSinkImplTest {
  private final FakeBaseStore base = new FakeBaseStore();
  private final OverrideLayer layer = new OverrideLayer();
  private final EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> broadcaster =
      EventBroadcasterImpl.forFlagChangeEvents(sharedExecutor, nullLogger);
  private final OverrideSinkImpl sink = new OverrideSinkImpl(layer, base, broadcaster, nullLogger);
  private final BlockingQueue<FlagChangeEvent> events = new LinkedBlockingQueue<>();

  private void listen() {
    broadcaster.register(events::add);
  }

  private Set<String> awaitKeys(int count) throws InterruptedException {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < count; i++) {
      FlagChangeEvent e = events.poll(5, TimeUnit.SECONDS);
      if (e == null) {
        throw new AssertionError("expected " + count + " flag change events but got " + keys);
      }
      keys.add(e.getKey());
    }
    return keys;
  }

  private void assertNoMoreEvents() throws InterruptedException {
    assertNull(events.poll(200, TimeUnit.MILLISECONDS));
  }

  private static Set<String> keys(String... keys) {
    Set<String> s = new HashSet<>();
    Collections.addAll(s, keys);
    return s;
  }

  @Test
  public void setOverridesReplacesLayer() {
    sink.setOverrides(flagsOnly(flagBuilder("a").build()));
    assertTrue(((FeatureFlag) layer.get(DataModel.FEATURES, "a").getItem()).isOverride());
    sink.setOverrides(Collections.emptyList());
    assertTrue(layer.isEmpty());
  }

  @Test
  public void notifiesOnAddChangeAndRemove() throws Exception {
    listen();

    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).on(true).build()));
    assertEquals(keys("a"), awaitKeys(1));

    // The same content again is not a change.
    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).on(true).build()));
    assertNoMoreEvents();

    // A content change at the same version is a change.
    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).on(false).build()));
    assertEquals(keys("a"), awaitKeys(1));

    // A version change is a change.
    sink.setOverrides(flagsOnly(flagBuilder("a").version(2).on(false).build()));
    assertEquals(keys("a"), awaitKeys(1));

    // Removal is a change.
    sink.setOverrides(Collections.emptyList());
    assertEquals(keys("a"), awaitKeys(1));
    assertNoMoreEvents();
  }

  @Test
  public void addedOverrideIdenticalToLaunchDarklyDataIsStillAChange() throws Exception {
    FeatureFlag ldFlag = flagBuilder("a").version(1).on(true).build();
    base.withFlag(ldFlag);
    listen();

    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).on(true).build()));
    assertEquals(keys("a"), awaitKeys(1));
  }

  @Test
  public void segmentOverrideFansOutToDependentFlags() throws Exception {
    FeatureFlag usesSegment = flagBuilder("uses-segment").on(true)
        .rules(ruleBuilder().id("r").variation(0).clauses(clauseMatchingSegment("seg")).build()).build();
    FeatureFlag unrelated = flagBuilder("unrelated").build();
    base.withFlag(usesSegment).withFlag(unrelated);
    listen();

    Segment segment = segmentBuilder("seg").included("u").build();
    sink.setOverrides(collections(Collections.<FeatureFlag>emptyList(), Collections.singletonList(segment)));

    assertEquals(keys("uses-segment"), awaitKeys(1));
    assertNoMoreEvents();
  }

  @Test
  public void prerequisiteFanOutUsesOldAndNewViews() throws Exception {
    // The LaunchDarkly definition of "parent" depends on "prereq". The override of "parent" removes
    // that dependency. Adding the override affects "parent". Later changing the override of
    // "prereq" does not affect "parent" while the parent override is in place. Removing the parent
    // override restores the edge, and "parent" is affected again.
    FeatureFlag ldParent = flagBuilder("parent").on(true).prerequisites(prerequisite("prereq", 0)).build();
    FeatureFlag ldPrereq = flagBuilder("prereq").on(true).build();
    base.withFlag(ldParent).withFlag(ldPrereq);
    listen();

    FeatureFlag parentOverride = flagBuilder("parent").version(5).on(true).build();
    sink.setOverrides(flagsOnly(parentOverride));
    assertEquals(keys("parent"), awaitKeys(1));

    sink.setOverrides(flagsOnly(parentOverride, flagBuilder("prereq").version(7).on(false).build()));
    assertEquals(keys("prereq"), awaitKeys(1));
    assertNoMoreEvents();

    // Removing both overrides: "prereq" changes, and "parent" depends on it in the new view.
    sink.setOverrides(Collections.emptyList());
    assertEquals(keys("parent", "prereq"), awaitKeys(2));
    assertNoMoreEvents();
  }

  @Test
  public void removingOverrideThatReferencedSegmentAffectsOnlyThatFlag() throws Exception {
    // The override of "flag" depends on segment "seg" that exists only in the base. Removing the
    // override affects "flag" through the old view. Nothing else is affected.
    base.withFlag(flagBuilder("flag").build());
    listen();
    FeatureFlag override = flagBuilder("flag").on(true)
        .rules(ruleBuilder().id("r").variation(0).clauses(clauseMatchingSegment("seg")).build()).build();
    sink.setOverrides(flagsOnly(override));
    assertEquals(keys("flag"), awaitKeys(1));

    sink.setOverrides(Collections.emptyList());
    assertEquals(keys("flag"), awaitKeys(1));
    assertNoMoreEvents();
  }

  @Test
  public void skipsDiffWorkWithoutListeners() {
    base.withFlag(flagBuilder("a").build());
    sink.setOverrides(flagsOnly(flagBuilder("a").version(2).build()));
    assertEquals(0, base.getAllCalls);
    assertEquals(2, layer.get(DataModel.FEATURES, "a").getVersion());
  }

  @Test
  public void toleratesBaseReadFailure() throws Exception {
    base.getAllError = new RuntimeException("store down");
    listen();

    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).build()));

    // The directly changed key is still reported and the layer is updated.
    assertEquals(keys("a"), awaitKeys(1));
    assertEquals(1, layer.get(DataModel.FEATURES, "a").getVersion());
  }

  @Test
  public void valueChangeInSameVersionIsDetectedThroughSerializedForm() throws Exception {
    listen();
    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).variations(LDValue.of("x")).build()));
    awaitKeys(1);
    sink.setOverrides(flagsOnly(flagBuilder("a").version(1).variations(LDValue.of("y")).build()));
    assertEquals(keys("a"), awaitKeys(1));
  }
}
