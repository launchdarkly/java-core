package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.server.TestComponents.TestEventProcessor;
import com.launchdarkly.sdk.server.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.OverrideTestDataSources.hangingSynchronizer;
import static com.launchdarkly.sdk.server.OverrideTestDataSources.initializerWith;
import static com.launchdarkly.sdk.server.TestComponents.specificComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs the OVERRIDE specification's test vectors. Each vector sets up LaunchDarkly data, an
 * override layer, and an initialization state, evaluates one flag through the full client stack,
 * and checks the value, the variation index, the reason, and the marking that the client hands to the
 * event processor for the evaluation.
 */
@SuppressWarnings("javadoc")
public class OverrideSpecVectorsTest extends BaseTest {
  private static final String VECTORS_RESOURCE = "/override-vectors/vectors.json";
  // The vectors' semantics are versioned. A schema change means this runner needs review.
  private static final String SUPPORTED_SCHEMA_VERSION = "0.4.0";

  static LDValue loadVectors() {
    try (InputStream in = OverrideSpecVectorsTest.class.getResourceAsStream(VECTORS_RESOURCE)) {
      if (in == null) {
        throw new AssertionError("vectors resource not found: " + VECTORS_RESOURCE);
      }
      Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
      return LDValue.parse(scanner.hasNext() ? scanner.next() : "");
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  static Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> collectionsFromJson(
      LDValue flags, LDValue flagValues, LDValue segments) {
    List<Map.Entry<String, ItemDescriptor>> flagItems = new ArrayList<>();
    for (String key : flags.keys()) {
      flagItems.add(new AbstractMap.SimpleEntry<>(key, FEATURES.deserialize(flags.get(key).toJsonString())));
    }
    for (String key : flagValues.keys()) {
      // A value-only entry is a flag that is on and serves the value as its single variation by
      // fallthrough.
      LDValue flag = LDValue.buildObject().put("key", key).put("version", 0).put("on", true)
          .put("variations", LDValue.buildArray().add(flagValues.get(key)).build())
          .put("fallthrough", LDValue.buildObject().put("variation", 0).build()).build();
      flagItems.add(new AbstractMap.SimpleEntry<>(key, FEATURES.deserialize(flag.toJsonString())));
    }
    List<Map.Entry<String, ItemDescriptor>> segmentItems = new ArrayList<>();
    for (String key : segments.keys()) {
      segmentItems.add(new AbstractMap.SimpleEntry<>(key, SEGMENTS.deserialize(segments.get(key).toJsonString())));
    }
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> result = new ArrayList<>();
    result.add(new AbstractMap.SimpleEntry<>(FEATURES, new KeyedItems<>(flagItems)));
    result.add(new AbstractMap.SimpleEntry<>(SEGMENTS, new KeyedItems<>(segmentItems)));
    return result;
  }

  @Test
  public void vectorsFileHasSupportedSchema() {
    LDValue file = loadVectors();
    assertEquals(SUPPORTED_SCHEMA_VERSION, file.get("schemaVersion").stringValue());
    assertTrue(file.get("vectors").size() > 0);
  }

  @Test
  public void allVectorsPass() throws Exception {
    LDValue file = loadVectors();
    List<String> failures = new ArrayList<>();
    for (LDValue vector : file.get("vectors").values()) {
      String name = vector.get("group").stringValue() + ": " + vector.get("description").stringValue();
      try {
        runVector(vector);
      } catch (AssertionError e) {
        failures.add(name + " -> " + e.getMessage());
      }
    }
    assertTrue("failing vectors:\n" + String.join("\n", failures), failures.isEmpty());
  }

  private void runVector(LDValue vector) throws Exception {
    LDValue ldData = vector.get("launchDarklyData");
    boolean initialized = ldData.get("initialized").booleanValue();
    LDValue overrides = vector.get("overrides");
    TestOverrideSource source = new TestOverrideSource(collectionsFromJson(
        overrides.get("flags"), overrides.get("flagValues"), overrides.get("segments")));

    DataSystemBuilder dataSystem = Components.dataSystem().custom().overrides(source);
    TestEventProcessor events = new TestEventProcessor();
    LDConfig.Builder config = new LDConfig.Builder()
        .events(specificComponent(events))
        .logging(Components.logging(testLogging).level(LDLogLevel.DEBUG));
    if (initialized) {
      dataSystem.initializers(initializerWith(collectionsFromJson(
          ldData.get("flags"), LDValue.buildObject().build(), ldData.get("segments"))));
    } else {
      // With no sources at all, the client would consider itself initialized with empty data rather
      // than apply its not-initialized handling. A synchronizer that never delivers anything avoids
      // that.
      dataSystem.synchronizers(hangingSynchronizer());
      config.startWait(Duration.ZERO);
    }
    config.dataSystem(dataSystem);

    LDValue evaluate = vector.get("evaluate");
    LDContext context = JsonSerialization.deserialize(evaluate.get("context").toJsonString(), LDContext.class);
    LDValue expect = vector.get("expect");

    try (LDClient client = new LDClient("sdk-key", config.build())) {
      assertEquals("initialization state", initialized, client.isInitialized());
      EvaluationDetail<LDValue> detail = client.jsonValueVariationDetail(
          evaluate.get("flagKey").stringValue(), context, evaluate.get("defaultValue"));

      assertEquals("value", expect.get("value"), detail.getValue());
      if (expect.get("variationIndex").isNull()) {
        assertTrue("variationIndex should be undefined", detail.isDefaultValue());
      } else {
        assertEquals("variationIndex", expect.get("variationIndex").intValue(), detail.getVariationIndex());
      }
      assertVectorReason(expect.get("reason"), LDValue.parse(JsonSerialization.serialize(detail.getReason())));

      // summaryOverrideAffected is the marking the client hands to the event processor for this
      // evaluation. The event processor keys individual event suppression and the summary counter
      // marker on that value, not on the reason.
      if (!expect.get("summaryOverrideAffected").isNull()) {
        List<Event.FeatureRequest> records = new ArrayList<>();
        for (Event e : events.events) {
          if (e instanceof Event.FeatureRequest
              && ((Event.FeatureRequest) e).getKey().equals(evaluate.get("flagKey").stringValue())) {
            records.add((Event.FeatureRequest) e);
          }
        }
        assertEquals("expected exactly one evaluation record for the flag", 1, records.size());
        assertEquals("summaryOverrideAffected", expect.get("summaryOverrideAffected").booleanValue(),
            records.get(0).isOverrideAffected());
      }
    }
  }

  // Compares the actual reason against only the fields present in the expected reason. The
  // override indicator collapses tri-state: an expected reason that omits it requires the actual
  // reason to report false, which is never serialized, or to omit it.
  private static void assertVectorReason(LDValue expected, LDValue actual) {
    for (String field : expected.keys()) {
      assertEquals("reason field " + field, expected.get(field), actual.get(field));
    }
    if (expected.get("overrideAffected").isNull()) {
      assertFalse("overrideAffected must be false or omitted", actual.get("overrideAffected").booleanValue());
    }
  }
}
