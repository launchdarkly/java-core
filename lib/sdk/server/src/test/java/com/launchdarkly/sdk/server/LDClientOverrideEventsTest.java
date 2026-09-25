package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.EvaluationReason.ErrorKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.TestComponents.TestEventProcessor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.EventSender;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import static com.launchdarkly.sdk.server.OverrideTestDataSources.initializerWith;
import static com.launchdarkly.sdk.server.TestComponents.specificComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The override-affected marking as the client hands it to the event processor, and the resulting
 * analytics output: no individual feature or debug event for a marked evaluation, and a summary
 * counter that carries the marker.
 */
@SuppressWarnings("javadoc")
public class LDClientOverrideEventsTest extends BaseTest {
  private static final String SDK_KEY = "sdk-key";
  private static final LDContext CONTEXT = LDContext.create("user-key");
  private static final LDValue DEFAULT = LDValue.of("default");

  private static FeatureFlag valueFlag(String key, int version, String value) {
    return flagBuilder(key).version(version).on(false).offVariation(0).variations(LDValue.of(value)).build();
  }

  private LDConfig.Builder baseBuilder(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> ldData,
      TestOverrideSource source) {
    return new LDConfig.Builder()
        .dataSystem(Components.dataSystem().custom().initializers(initializerWith(ldData)).overrides(source))
        .logging(Components.logging(testLogging).level(LDLogLevel.DEBUG));
  }

  private static List<Event.FeatureRequest> featureRecords(TestEventProcessor events, String flagKey) {
    List<Event.FeatureRequest> result = new ArrayList<>();
    for (Event e : events.events) {
      if (e instanceof Event.FeatureRequest && ((Event.FeatureRequest) e).getKey().equals(flagKey)) {
        result.add((Event.FeatureRequest) e);
      }
    }
    return result;
  }

  private static Event.FeatureRequest singleRecord(TestEventProcessor events, String flagKey) {
    List<Event.FeatureRequest> records = featureRecords(events, flagKey);
    assertEquals("expected one record for " + flagKey, 1, records.size());
    return records.get(0);
  }

  @Test
  public void evaluationOfOverriddenFlagIsRecordedAsOverrideAffected() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", 5, "override-value")));
    Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> ldData = flagsOnly(
        valueFlag("overridden", 1, "ld-value"), valueFlag("plain", 1, "plain-value"));

    try (LDClient client = new LDClient(SDK_KEY, baseBuilder(ldData, source).events(specificComponent(events)).build())) {
      client.stringVariation("overridden", CONTEXT, "default");
      client.stringVariation("plain", CONTEXT, "default");

      Event.FeatureRequest overridden = singleRecord(events, "overridden");
      assertTrue(overridden.isOverrideAffected());
      assertEquals(5, overridden.getVersion());
      assertEquals(LDValue.of("override-value"), overridden.getValue());

      Event.FeatureRequest plain = singleRecord(events, "plain");
      assertFalse(plain.isOverrideAffected());
    }
  }

  @Test
  public void recordedMarkingDoesNotDependOnRequestingTheReason() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", 5, "override-value")));

    try (LDClient client = new LDClient(SDK_KEY, baseBuilder(flagsOnly(), source).events(specificComponent(events)).build())) {
      client.stringVariation("overridden", CONTEXT, "default");
      Event.FeatureRequest record = singleRecord(events, "overridden");
      assertTrue(record.isOverrideAffected());
      assertNull(record.getReason());
    }
  }

  @Test
  public void prerequisiteRecordsCarryTheirOwnMarking() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    FeatureFlag parent = flagBuilder("parent").version(1).on(true).fallthroughVariation(1).offVariation(0)
        .variations(LDValue.of("off"), LDValue.of("on"))
        .prerequisites(prerequisite("overridden-prereq", 1), prerequisite("plain-prereq", 1)).build();
    FeatureFlag ldOverriddenPrereq = flagBuilder("overridden-prereq").version(1).on(false).offVariation(0)
        .variations(LDValue.of("a"), LDValue.of("b")).build();
    FeatureFlag plainPrereq = flagBuilder("plain-prereq").version(1).on(true).fallthroughVariation(1).offVariation(0)
        .variations(LDValue.of("a"), LDValue.of("b")).build();
    FeatureFlag overriddenPrereq = flagBuilder("overridden-prereq").version(2).on(true).fallthroughVariation(1)
        .offVariation(0).variations(LDValue.of("a"), LDValue.of("b")).build();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(overriddenPrereq));

    try (LDClient client = new LDClient(SDK_KEY,
        baseBuilder(flagsOnly(parent, ldOverriddenPrereq, plainPrereq), source).events(specificComponent(events)).build())) {
      assertEquals("on", client.stringVariation("parent", CONTEXT, "default"));

      assertTrue(singleRecord(events, "parent").isOverrideAffected());
      Event.FeatureRequest overriddenRecord = singleRecord(events, "overridden-prereq");
      assertTrue(overriddenRecord.isOverrideAffected());
      assertEquals("parent", overriddenRecord.getPrereqOf());
      assertEquals(2, overriddenRecord.getVersion());
      Event.FeatureRequest plainRecord = singleRecord(events, "plain-prereq");
      assertFalse(plainRecord.isOverrideAffected());
      assertEquals("parent", plainRecord.getPrereqOf());
    }
  }

  @Test
  public void segmentReadMarksTheRecord() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    FeatureFlag usesSegment = flagBuilder("uses-segment").version(1).on(true).fallthroughVariation(0).offVariation(0)
        .variations(LDValue.of("no"), LDValue.of("yes"))
        .rules(ruleBuilder().id("r").variation(1).clauses(clauseMatchingSegment("seg")).build()).build();
    Segment ldSegment = segmentBuilder("seg").version(1).build();
    Segment overrideSegment = segmentBuilder("seg").version(2).build(); // still does not include the context
    TestOverrideSource source = new TestOverrideSource(
        collections(Collections.<FeatureFlag>emptyList(), Collections.singletonList(overrideSegment)));

    try (LDClient client = new LDClient(SDK_KEY, baseBuilder(
        collections(Collections.singletonList(usesSegment), Collections.singletonList(ldSegment)), source)
        .events(specificComponent(events)).build())) {
      assertEquals("no", client.stringVariation("uses-segment", CONTEXT, "default"));
      assertTrue(singleRecord(events, "uses-segment").isOverrideAffected());
    }
  }

  @Test
  public void typeMismatchRecordKeepsMarking() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", 5, "a string")));

    try (LDClient client = new LDClient(SDK_KEY, baseBuilder(flagsOnly(), source).events(specificComponent(events)).build())) {
      assertTrue(client.boolVariation("overridden", CONTEXT, true));
      Event.FeatureRequest record = singleRecord(events, "overridden");
      assertTrue(record.isOverrideAffected());
      assertEquals(EvaluationReason.error(ErrorKind.WRONG_TYPE).withOverrideAffected(true),
          client.boolVariationDetail("overridden", CONTEXT, true).getReason());
    }
  }

  @Test
  public void unknownFlagRecordIsNotMarked() throws Exception {
    TestEventProcessor events = new TestEventProcessor();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", 5, "v")));
    try (LDClient client = new LDClient(SDK_KEY, baseBuilder(flagsOnly(), source).events(specificComponent(events)).build())) {
      client.stringVariation("missing", CONTEXT, "default");
      assertFalse(singleRecord(events, "missing").isOverrideAffected());
    }
  }

  /**
   * Captures the analytics payloads that the default event processor sends.
   */
  private static final class CapturingEventSender implements EventSender {
    final BlockingQueue<LDValue> payloads = new LinkedBlockingQueue<>();

    @Override
    public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
      payloads.add(LDValue.parse(new String(data, StandardCharsets.UTF_8)));
      return Result.SUCCESS;
    }

    @Override
    public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
      return Result.SUCCESS;
    }

    @Override
    public void close() {
    }
  }

  private static List<LDValue> eventsOfKind(LDValue payload, String kind) {
    List<LDValue> result = new ArrayList<>();
    for (LDValue e : payload.values()) {
      if (e.get("kind").stringValue().equals(kind)) {
        result.add(e);
      }
    }
    return result;
  }

  @Test
  public void overrideAffectedEvaluationsAppearOnlyInSummaryOutput() throws Exception {
    long debugUntil = System.currentTimeMillis() + 100000;
    // Both flags request individual feature events and debug events.
    FeatureFlag overridden = flagBuilder("overridden").version(300).on(false).offVariation(0)
        .variations(LDValue.of("override-value")).trackEvents(true).debugEventsUntilDate(debugUntil).build();
    FeatureFlag plain = flagBuilder("plain").version(100).on(false).offVariation(0)
        .variations(LDValue.of("plain-value")).trackEvents(true).debugEventsUntilDate(debugUntil).build();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(overridden));
    CapturingEventSender sender = new CapturingEventSender();

    LDConfig config = baseBuilder(flagsOnly(plain), source)
        .events(Components.sendEvents().eventSender(specificComponent(sender)).flushInterval(Duration.ofHours(1)))
        .diagnosticOptOut(true)
        .build();

    try (LDClient client = new LDClient(SDK_KEY, config)) {
      client.stringVariation("overridden", CONTEXT, "default");
      client.stringVariation("overridden", CONTEXT, "default");
      client.stringVariation("plain", CONTEXT, "default");
      client.flush();

      LDValue payload = sender.payloads.poll(5, TimeUnit.SECONDS);
      assertNotNull("no analytics payload was sent", payload);

      // The overridden flag produced no feature event and no debug event. The plain flag produced both.
      List<LDValue> featureEvents = eventsOfKind(payload, "feature");
      List<LDValue> debugEvents = eventsOfKind(payload, "debug");
      assertEquals(1, featureEvents.size());
      assertEquals("plain", featureEvents.get(0).get("key").stringValue());
      assertEquals(1, debugEvents.size());
      assertEquals("plain", debugEvents.get(0).get("key").stringValue());
      assertEquals(1, eventsOfKind(payload, "index").size());

      // Both flags are summarized. Only the overridden flag's counter carries the marker.
      List<LDValue> summaries = eventsOfKind(payload, "summary");
      assertEquals(1, summaries.size());
      LDValue features = summaries.get(0).get("features");
      LDValue overriddenCounters = features.get("overridden").get("counters");
      assertEquals(1, overriddenCounters.size());
      LDValue overriddenCounter = overriddenCounters.get(0);
      assertTrue(overriddenCounter.get("overrideAffected").booleanValue());
      assertEquals(300, overriddenCounter.get("version").intValue());
      assertEquals(2, overriddenCounter.get("count").intValue());
      assertEquals(LDValue.of("override-value"), overriddenCounter.get("value"));
      LDValue plainCounter = features.get("plain").get("counters").get(0);
      assertTrue(plainCounter.get("overrideAffected").isNull());
      assertEquals(1, plainCounter.get("count").intValue());
    }
  }
}
