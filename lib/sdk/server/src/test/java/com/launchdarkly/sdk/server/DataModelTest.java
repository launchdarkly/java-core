package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.launchdarkly.sdk.server.DataModel.Clause;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Rollout;
import com.launchdarkly.sdk.server.DataModel.RolloutKind;
import com.launchdarkly.sdk.server.DataModel.Rule;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.DataModel.SegmentRule;
import com.launchdarkly.sdk.server.DataModel.Target;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;

import org.junit.Test;

import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class DataModelTest {
  @Test
  public void flagPrerequisitesListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), flagWithAllZeroValuedFields().getPrerequisites());
  }

  @Test
  public void flagTargetsListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), flagWithAllZeroValuedFields().getTargets());
  }

  @Test
  public void flagContextTargetsListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), flagWithAllZeroValuedFields().getContextTargets());
  }
  
  @Test
  public void flagRulesListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), flagWithAllZeroValuedFields().getRules());
  }

  @Test
  public void flagVariationsListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), flagWithAllZeroValuedFields().getVariations());
  }
  
  @Test
  public void targetKeysSetCanNeverBeNull() {
    Target t = new Target(null, null, 0);
    assertEquals(ImmutableSet.of(), t.getValues());
  }
  
  @Test
  public void ruleClausesListCanNeverBeNull() {
    Rule r = new Rule("id", null, null, null, false);
    assertEquals(ImmutableList.of(), r.getClauses());
  }
  
  @Test
  public void clauseValuesListCanNeverBeNull() {
    Clause c = new Clause(null, null, null, null, false);
    assertEquals(ImmutableList.of(), c.getValues());
  }

  @Test
  public void segmentIncludedCanNeverBeNull() {
    assertEquals(ImmutableSet.of(), segmentWithAllZeroValuedFields().getIncluded());
  }

  @Test
  public void segmentExcludedCanNeverBeNull() {
    assertEquals(ImmutableSet.of(), segmentWithAllZeroValuedFields().getExcluded());
  }

  @Test
  public void segmentIncludedContextsCanNeverBeNull() {
    assertEquals(ImmutableList.of(), segmentWithAllZeroValuedFields().getIncludedContexts());
  }

  @Test
  public void segmentExcludedContextsCanNeverBeNull() {
    assertEquals(ImmutableList.of(), segmentWithAllZeroValuedFields().getExcludedContexts());
  }

  @Test
  public void segmentRulesListCanNeverBeNull() {
    assertEquals(ImmutableList.of(), segmentWithAllZeroValuedFields().getRules());
  }

  @Test
  public void segmentRuleClausesListCanNeverBeNull() {
    SegmentRule r = new SegmentRule(null, null, null, null);
    assertEquals(ImmutableList.of(), r.getClauses());
  }
  
  @Test
  public void rolloutVariationsListCanNeverBeNull() {
    Rollout r = new Rollout(null, null, null, RolloutKind.rollout, null);
    assertEquals(ImmutableList.of(), r.getVariations());
  }
  
  private FeatureFlag flagWithAllZeroValuedFields() {
    // This calls the empty constructor directly to simulate a condition where Gson did not set any fields
    // and no preprocessing has happened.
    return new FeatureFlag();
  }
  
  private Segment segmentWithAllZeroValuedFields() {
    // This calls the empty constructor directly to simulate a condition where Gson did not set any fields
    // and no preprocessing has happened.
    return new Segment();
  }

  @Test
  public void flagAndSegmentAreNotOverridesByDefault() {
    assertFalse(flagBuilder("f").build().isOverride());
    assertFalse(segmentBuilder("s").build().isOverride());
    assertFalse(((FeatureFlag) DataModel.FEATURES.deserialize("{\"key\":\"f\",\"version\":1}").getItem()).isOverride());
    assertFalse(((Segment) DataModel.SEGMENTS.deserialize("{\"key\":\"s\",\"version\":1}").getItem()).isOverride());
  }

  @Test
  public void markedFlagCopyCarriesMarkerAndSharesDataWithoutMutatingSource() {
    FeatureFlag source = flagBuilder("f").version(7).on(true).variations(LDValue.of("a"), LDValue.of("b"))
        .fallthroughVariation(1).offVariation(0).prerequisites(prerequisite("p", 1)).trackEvents(true)
        .debugEventsUntilDate(1000L).build();

    FeatureFlag marked = source.markedAsOverride();

    assertTrue(marked.isOverride());
    assertFalse(source.isOverride());
    assertNotSame(source, marked);
    assertEquals(source.getKey(), marked.getKey());
    assertEquals(source.getVersion(), marked.getVersion());
    assertEquals(source.isOn(), marked.isOn());
    assertSame(source.getVariations(), marked.getVariations());
    assertSame(source.getPrerequisites(), marked.getPrerequisites());
    assertSame(source.getFallthrough(), marked.getFallthrough());
    assertEquals(source.getOffVariation(), marked.getOffVariation());
    assertEquals(source.isTrackEvents(), marked.isTrackEvents());
    assertEquals(source.getDebugEventsUntilDate(), marked.getDebugEventsUntilDate());
    assertSame(source.preprocessed, marked.preprocessed);
  }

  @Test
  public void markedFlagCopyOfUnpreprocessedFlagHasNoPreprocessing() {
    FeatureFlag source = flagBuilder("f").disablePreprocessing(true).build();
    FeatureFlag marked = source.markedAsOverride();
    assertNull(marked.preprocessed);
    assertNull(source.preprocessed);
    assertTrue(marked.isOverride());
  }

  @Test
  public void markedSegmentCopyCarriesMarkerAndSharesDataWithoutMutatingSource() {
    Segment source = segmentBuilder("s").version(3).included("u1").excluded("u2").unbounded(false).build();

    Segment marked = source.markedAsOverride();

    assertTrue(marked.isOverride());
    assertFalse(source.isOverride());
    assertNotSame(source, marked);
    assertEquals(source.getKey(), marked.getKey());
    assertEquals(source.getVersion(), marked.getVersion());
    assertSame(source.getIncluded(), marked.getIncluded());
    assertSame(source.getExcluded(), marked.getExcluded());
    assertSame(source.getRules(), marked.getRules());
  }

  @Test
  public void overrideMarkerIsNeverSerialized() {
    FeatureFlag flag = flagBuilder("f").version(7).build().markedAsOverride();
    String flagJson = DataModel.FEATURES.serialize(new ItemDescriptor(flag.getVersion(), flag));
    assertFalse(flagJson.toLowerCase().contains("override"));
    assertEquals(LDValue.of("f"), LDValue.parse(flagJson).get("key"));

    Segment segment = segmentBuilder("s").version(3).build().markedAsOverride();
    String segmentJson = DataModel.SEGMENTS.serialize(new ItemDescriptor(segment.getVersion(), segment));
    assertFalse(segmentJson.toLowerCase().contains("override"));
  }

  @Test
  public void overrideMarkerInJsonIsIgnoredWhenDeserializing() {
    FeatureFlag flag = (FeatureFlag) DataModel.FEATURES.deserialize(
        "{\"key\":\"f\",\"version\":1,\"isOverride\":true}").getItem();
    assertFalse(flag.isOverride());
    Segment segment = (Segment) DataModel.SEGMENTS.deserialize(
        "{\"key\":\"s\",\"version\":1,\"isOverride\":true}").getItem();
    assertFalse(segment.isOverride());
  }
}
