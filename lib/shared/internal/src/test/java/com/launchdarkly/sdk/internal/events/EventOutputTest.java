package com.launchdarkly.sdk.internal.events;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextBuilder;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.internal.events.Event.FeatureRequest;
import com.launchdarkly.sdk.internal.events.EventSummarizer.EventSummary;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;

import static com.launchdarkly.sdk.EvaluationDetail.NO_VARIATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("javadoc")
public class EventOutputTest extends BaseEventTest {
  private static final Gson gson = new Gson();

  private final ContextBuilder contextBuilderWithAllAttributes = LDContext.builder("userkey")
      .name("me")
      .set("custom1", "value1")
      .set("custom2", "value2");
  private static final LDValue contextJsonWithAllAttributes = parseValue("{" +
      "\"kind\":\"user\"," +
      "\"key\":\"userkey\"," +
      "\"custom1\":\"value1\"," +
      "\"custom2\":\"value2\"," +
      "\"name\":\"me\"" +
      "}");

  @Test
  public void allAttributesAreSerialized() throws Exception {
    testInlineContextSerialization(contextBuilderWithAllAttributes.build(), contextJsonWithAllAttributes,
        defaultEventsConfig());
  }

  @Test
  public void allAttributesPrivateMakesAttributesPrivate() throws Exception {
    // We test this behavior in more detail in EventContextFormatterTest, but here we're verifying that the
    // EventOutputFormatter is actually using EventContextFormatter and configuring it correctly.
    LDContext context = LDContext.builder("userkey")
      .name("me")
      .build();
    LDValue expectedJson = LDValue.buildObject()
        .put("kind", "user")
        .put("key", context.getKey())
        .put("_meta", LDValue.parse("{\"redactedAttributes\":[\"name\"]}"))
        .build();
    EventsConfiguration config = makeEventsConfig(true, null);
    testInlineContextSerialization(context, expectedJson, config);
  }

  @Test
  public void globalPrivateAttributeNamesMakeAttributesPrivate() throws Exception {
    // See comment in allAttributesPrivateMakesAttributesPrivate
    LDContext context = LDContext.builder("userkey")
        .name("me")
        .set("attr1", "value1")
        .build();
    LDValue expectedJson = LDValue.buildObject()
        .put("kind", "user")
        .put("key", context.getKey())
        .put("name", "me")
        .put("_meta", LDValue.parse("{\"redactedAttributes\":[\"attr1\"]}"))
        .build();
    EventsConfiguration config = makeEventsConfig(false, ImmutableSet.of(AttributeRef.fromLiteral("attr1")));
    testInlineContextSerialization(context, expectedJson, config);
  }

  @Test
  public void perContextPrivateAttributesMakeAttributePrivate() throws Exception {
    // See comment in allAttributesPrivateMakesAttributesPrivate
    LDContext context = LDContext.builder("userkey")
        .name("me")
        .set("attr1", "value1")
        .privateAttributes("attr1")
        .build();
    LDValue expectedJson = LDValue.buildObject()
        .put("kind", "user")
        .put("key", context.getKey())
        .put("name", "me")
        .put("_meta", LDValue.parse("{\"redactedAttributes\":[\"attr1\"]}"))
        .build();
    EventsConfiguration config = makeEventsConfig(false, null);
    testInlineContextSerialization(context, expectedJson, config);
  }

  private ObjectBuilder buildFeatureEventProps(String key, String userKey) {
    return LDValue.buildObject()
        .put("kind", "feature")
        .put("key", key)
        .put("creationDate", 100000)
        .put("context", LDValue.buildObject().put("kind", "user").put("key", userKey).build());
  }

  private ObjectBuilder buildFeatureEventProps(String key) {
    return buildFeatureEventProps(key, "userkey");
  }

  @Test
  public void featureEventIsSerialized() throws Exception {
    LDContext context = LDContext.builder("userkey").name("me").build();
    LDValue value = LDValue.of("flagvalue"), defaultVal = LDValue.of("defaultvalue");
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());
    
    FeatureRequest feWithVariation = featureEvent(context, FLAG_KEY).flagVersion(FLAG_VERSION).variation(1)
        .value(value).defaultValue(defaultVal).build();
    LDValue feJson1 = buildFeatureEventProps(FLAG_KEY)
              .put("version", FLAG_VERSION)
              .put("variation", 1)
              .put("value", value)
              .put("default", defaultVal)
              .put("context", LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build())
              .build();
    assertJsonEquals(feJson1, getSingleOutputEvent(f, feWithVariation));

    FeatureRequest feWithoutVariationOrDefault = featureEvent(context, FLAG_KEY).flagVersion(FLAG_VERSION)
        .variation(NO_VARIATION).value(value).defaultValue(null).build();
    LDValue feJson2 = buildFeatureEventProps(FLAG_KEY)
        .put("version", FLAG_VERSION)
        .put("value", value)
        .put("context", LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build())
        .build();
    assertJsonEquals(feJson2, getSingleOutputEvent(f, feWithoutVariationOrDefault));

    FeatureRequest feWithReason = featureEvent(context, FLAG_KEY).flagVersion(FLAG_VERSION).variation(1)
        .value(value).defaultValue(defaultVal).reason(EvaluationReason.fallthrough()).build();
    LDValue feJson3 = buildFeatureEventProps(FLAG_KEY)
        .put("version", FLAG_VERSION)
        .put("variation", 1)
        .put("value", value)
        .put("default", defaultVal)
        .put("reason", LDValue.buildObject().put("kind", "FALLTHROUGH").build())
        .put("context", LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build())
        .build();
    assertJsonEquals(feJson3, getSingleOutputEvent(f, feWithReason));

    Event.FeatureRequest debugEvent = feWithVariation.toDebugEvent();
    LDValue feJson5 = LDValue.buildObject()
        .put("kind", "debug")
        .put("key", FLAG_KEY)
        .put("creationDate", 100000)
        .put("version", FLAG_VERSION)
        .put("variation", 1)
        .put("context", LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build())
        .put("value", value)
        .put("default", defaultVal)
        .build();
    assertJsonEquals(feJson5, getSingleOutputEvent(f, debugEvent));

    Event.FeatureRequest prereqEvent = featureEvent(context, FLAG_KEY).flagVersion(FLAG_VERSION)
        .variation(1).value(value).defaultValue(null).prereqOf("parent").build();
    LDValue feJson6 = buildFeatureEventProps(FLAG_KEY)
        .put("version", 11)
        .put("variation", 1)
        .put("value", "flagvalue")
        .put("prereqOf", "parent")
        .put("context", LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build())
        .build();
    assertJsonEquals(feJson6, getSingleOutputEvent(f, prereqEvent));
  }

  @Test
  public void featureEventRedactsAnonymousContextAttributes() throws Exception {
    LDValue value = LDValue.of("flagvalue"), defaultVal = LDValue.of("defaultvalue");

    // Single-kind context redaction
    LDContext user_context = LDContext.builder("userkey").anonymous(true).name("me").set("age", 42).build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    FeatureRequest feWithVariation1 = featureEvent(user_context, FLAG_KEY).flagVersion(FLAG_VERSION).variation(1)
        .value(value).defaultValue(defaultVal).build();
    LDValue contextJson = LDValue.buildObject()
        .put("kind", "user")
        .put("key", "userkey")
        .put("anonymous", true)
        .put("_meta", LDValue.parse("{\"redactedAttributes\":[\"name\", \"age\"]}"))
        .build();
    LDValue feJson1 = buildFeatureEventProps(FLAG_KEY)
              .put("version", FLAG_VERSION)
              .put("variation", 1)
              .put("value", value)
              .put("default", defaultVal)
              .put("context", contextJson)
              .build();
    assertJsonEquals(feJson1, getSingleOutputEvent(f, feWithVariation1));

    // Multi-kind context redaction
    LDContext org_context = LDContext.builder("orgkey").anonymous(false).kind("org").name("me").set("age", 42).build();
    LDContext multi_context = LDContext.createMulti(user_context, org_context);

    FeatureRequest feWithVariation2 = featureEvent(multi_context, FLAG_KEY).flagVersion(FLAG_VERSION).variation(1)
        .value(value).defaultValue(defaultVal).build();
    LDValue userJson = LDValue.buildObject()
        .put("key", "userkey")
        .put("anonymous", true)
        .put("_meta", LDValue.parse("{\"redactedAttributes\":[\"name\", \"age\"]}"))
        .build();
    LDValue orgJson = LDValue.buildObject()
        .put("key", "orgkey")
        .put("name", "me")
        .put("age", 42)
        .build();
    contextJson = LDValue.buildObject()
        .put("kind", "multi")
        .put("user", userJson)
        .put("org", orgJson)
        .build();

    LDValue feJson2 = buildFeatureEventProps(FLAG_KEY)
              .put("version", FLAG_VERSION)
              .put("variation", 1)
              .put("value", value)
              .put("default", defaultVal)
              .put("context", contextJson)
              .build();
    assertJsonEquals(feJson2, getSingleOutputEvent(f, feWithVariation2));
  }

  @Test
  public void identifyEventIsSerialized() throws IOException {
    LDContext context = LDContext.builder("userkey").name("me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.Identify ie = identifyEvent(context);
    LDValue ieJson = parseValue("{" +
        "\"kind\":\"identify\"," +
        "\"creationDate\":100000," +
        "\"context\":{\"kind\":\"user\",\"key\":\"userkey\",\"name\":\"me\"}" +
        "}");
    assertJsonEquals(ieJson, getSingleOutputEvent(f, ie));
  }

  @Test
  public void customEventIsSerialized() throws IOException {
    LDContext context = LDContext.builder("userkey").name("me").build();
    LDValue contextJson = LDValue.buildObject().put("kind", "user").put("key", "userkey").put("name", "me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.Custom ceWithoutData = customEvent(context, "customkey").build();
    LDValue ceJson1 = parseValue("{" +
        "\"kind\":\"custom\"," +
        "\"creationDate\":100000," +
        "\"key\":\"customkey\"," +
        "\"context\":" + contextJson +
        "}");
    assertJsonEquals(ceJson1, getSingleOutputEvent(f, ceWithoutData));

    Event.Custom ceWithData = customEvent(context, "customkey").data(LDValue.of("thing")).build();
    LDValue ceJson2 = parseValue("{" +
        "\"kind\":\"custom\"," +
        "\"creationDate\":100000," +
        "\"key\":\"customkey\"," +
        "\"context\":" + contextJson + "," +
        "\"data\":\"thing\"" +
        "}");
    assertJsonEquals(ceJson2, getSingleOutputEvent(f, ceWithData));

    Event.Custom ceWithMetric = customEvent(context, "customkey").metricValue(2.5).build();
    LDValue ceJson3 = parseValue("{" +
        "\"kind\":\"custom\"," +
        "\"creationDate\":100000," +
        "\"key\":\"customkey\"," +
        "\"context\":" + contextJson + "," +
        "\"metricValue\":2.5" +
        "}");
    assertJsonEquals(ceJson3, getSingleOutputEvent(f, ceWithMetric));

    Event.Custom ceWithDataAndMetric = customEvent(context, "customkey").data(LDValue.of("thing"))
        .metricValue(2.5).build();
    LDValue ceJson4 = parseValue("{" +
        "\"kind\":\"custom\"," +
        "\"creationDate\":100000," +
        "\"key\":\"customkey\"," +
        "\"context\":" + contextJson + "," +
        "\"data\":\"thing\"," +
        "\"metricValue\":2.5" +
        "}");
    assertJsonEquals(ceJson4, getSingleOutputEvent(f, ceWithDataAndMetric));
  }

  @Test
  public void summaryEventIsSerialized() throws Exception {
    LDValue value1a = LDValue.of("value1a"), value2a = LDValue.of("value2a"), value2b = LDValue.of("value2b"),
        default1 = LDValue.of("default1"), default2 = LDValue.of("default2"), default3 = LDValue.of("default3");
    LDContext context1 = LDContext.create("key1");
    LDContext context2 = LDContext.createMulti(context1, LDContext.create(ContextKind.of("kind2"), "key2"));

    EventSummarizer es = new EventSummarizer();

    es.summarizeEvent(1000, "first", 11, 1, value1a, default1, context1); // context1 has kind "user"

    es.summarizeEvent(1000, "second", 21, 1, value2a, default2, context1);

    es.summarizeEvent(1001, "first", 11, 1, value1a, default1, context1);
    es.summarizeEvent(1001, "first", 12, 1, value1a, default1, context2); // context2 has kind "user" and kind "kind2"

    es.summarizeEvent(1001, "second", 21, 2, value2b, default2, context1);
    es.summarizeEvent(1002, "second", 21, -1, default2, default2, context1);

    es.summarizeEvent(1002, "third", -1, -1, default3, default3, context1);

    EventSummary summary = es.getSummaryAndReset();

    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());
    StringWriter w = new StringWriter();
    int count = f.writeOutputEvents(new Event[0], Collections.singletonList(summary), w);
    assertEquals(1, count);
    LDValue outputEvent = parseValue(w.toString()).get(0);

    assertEquals("summary", outputEvent.get("kind").stringValue());
    assertEquals(1000, outputEvent.get("startDate").intValue());
    assertEquals(1002, outputEvent.get("endDate").intValue());

    LDValue featuresJson = outputEvent.get("features");
    assertEquals(3, featuresJson.size());

    LDValue firstJson = featuresJson.get("first");
    assertEquals("default1", firstJson.get("default").stringValue());
    assertThat(firstJson.get("contextKinds").values(), containsInAnyOrder(
        LDValue.of("user"), LDValue.of("kind2")));
    assertThat(firstJson.get("counters").values(), containsInAnyOrder(
        parseValue("{\"value\":\"value1a\",\"variation\":1,\"version\":11,\"count\":2}"),
        parseValue("{\"value\":\"value1a\",\"variation\":1,\"version\":12,\"count\":1}")
    ));

    LDValue secondJson = featuresJson.get("second");
    assertEquals("default2", secondJson.get("default").stringValue());
    assertThat(secondJson.get("contextKinds").values(), contains(LDValue.of("user")));
    assertThat(secondJson.get("counters").values(), containsInAnyOrder(
            parseValue("{\"value\":\"value2a\",\"variation\":1,\"version\":21,\"count\":1}"),
            parseValue("{\"value\":\"value2b\",\"variation\":2,\"version\":21,\"count\":1}"),
            parseValue("{\"value\":\"default2\",\"version\":21,\"count\":1}")
        ));

    LDValue thirdJson = featuresJson.get("third");
    assertEquals("default3", thirdJson.get("default").stringValue());
    assertThat(thirdJson.get("contextKinds").values(), contains(LDValue.of("user")));
    assertThat(thirdJson.get("counters").values(), contains(
        parseValue("{\"unknown\":true,\"value\":\"default3\",\"count\":1}")
    ));
  }

  @Test
  public void migrationOpEventIsSerialized() throws IOException {
    LDContext context = LDContext.builder("user-key").name("me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.MigrationOp event = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        new Event.MigrationOp.ConsistencyMeasurement(true, 1),
        new Event.MigrationOp.LatencyMeasurement(100l, 50l),
        new Event.MigrationOp.ErrorMeasurement(false, true)
    );

    LDValue received = getSingleOutputEvent(f, event);
    LDValue expected = LDValue.buildObject()
        .put("operation", "read")
        .put("kind", "migration_op")
        .put("creationDate", 0)
        .put("evaluation", LDValue.buildObject()
            .put("key", "migration-key")
            .put("variation", 1)
            .put("version", 2)
            .put("value", "live")
            .put("default", "off")
            .put("reason", LDValue.buildObject()
                .put("kind", "FALLTHROUGH")
                .build()).build())
        .put("context", LDValue.buildObject()
            .put("kind", "user")
            .put("key", "user-key")
            .put("name", "me")
            .build())
        .put("samplingRatio", 2)
        .put("measurements", LDValue.buildArray()
            .add(LDValue.buildObject()
                .put("key", "invoked")
                .put("values", LDValue.buildObject()
                    .put("new", true)
                    .build())
                .build())
            .add(LDValue.buildObject()
                .put("key", "consistent")
                .put("value", true)
                .build())
            .add(LDValue.buildObject()
                .put("key", "latency_ms")
                .put("values", LDValue.buildObject()
                    .put("old", 100)
                    .put("new", 50)
                    .build())
                .build())
            .add(LDValue.buildObject()
                .put("key", "error")
                .put("values", LDValue.buildObject()
                    .put("new", true)
                    .build())
                .build())
            .build())
          .build();

    assertJsonEquals(expected, received);
  }

  @Test
  public void migrationOpEventSerializationCanExcludeOptionalItems() throws IOException {
    LDContext context = LDContext.builder("user-key").name("me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.MigrationOp event = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        -1,
        -1,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        1,
        "read",
        new Event.MigrationOp.InvokedMeasurement(true, false),
        null,
        null,
        null
    );

    LDValue received1 = getSingleOutputEvent(f, event);
    Event.MigrationOp event2 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        -1,
        -1,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        1,
        "read",
        new Event.MigrationOp.InvokedMeasurement(true, false),
        null,
        // Null measurement, versus a measurement containing no values, should behave the same.
        new Event.MigrationOp.LatencyMeasurement(null, null),
        new Event.MigrationOp.ErrorMeasurement(false, false)
    );
    LDValue received2 = getSingleOutputEvent(f, event2);

    LDValue expected = LDValue.buildObject()
        .put("operation", "read")
        .put("kind", "migration_op")
        .put("creationDate", 0)
        .put("evaluation", LDValue.buildObject()
            .put("key", "migration-key")
            .put("value", "live")
            .put("default", "off")
            .put("reason", LDValue.buildObject()
                .put("kind", "FALLTHROUGH")
                .build()).build())
        .put("context", LDValue.buildObject()
            .put("kind", "user")
            .put("key", "user-key")
            .put("name", "me")
            .build())
        .put("measurements", LDValue.buildArray()
            .add(LDValue.buildObject()
                .put("key", "invoked")
                .put("values", LDValue.buildObject()
                    .put("old", true)
                    .build())
                .build())
            .build())
        .build();

    assertJsonEquals(expected, received1);
    assertJsonEquals(expected, received2);
  }

  @Test
  public void migrationOpEventCanSerializeDifferentLatencyPermutations() throws IOException {
    LDContext context = LDContext.builder("user-key").name("me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.MigrationOp event1 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        new Event.MigrationOp.LatencyMeasurement(null, 50l),
        null
    );

    LDValue received1 = getSingleOutputEvent(f, event1);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "latency_ms")
        .put("values", LDValue.buildObject()
            .put("new", 50)
            .build())
        .build(), received1.get("measurements").get(1));

    Event.MigrationOp event2 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        new Event.MigrationOp.LatencyMeasurement(50l, null),
        null
    );

    LDValue received2 = getSingleOutputEvent(f, event2);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "latency_ms")
        .put("values", LDValue.buildObject()
            .put("old", 50)
            .build())
        .build(), received2.get("measurements").get(1));

    Event.MigrationOp event3 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        new Event.MigrationOp.LatencyMeasurement(50l, 150l),
        null
    );

    LDValue received3 = getSingleOutputEvent(f, event3);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "latency_ms")
        .put("values", LDValue.buildObject()
            .put("old", 50)
            .put("new", 150)
            .build())
        .build(), received3.get("measurements").get(1));
  }

  @Test
  public void migrationOpEventCanSerializeDifferentErrorPermutations() throws IOException {
    LDContext context = LDContext.builder("user-key").name("me").build();
    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());

    Event.MigrationOp event1 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        null,
        new Event.MigrationOp.ErrorMeasurement(true, false)
    );

    LDValue received1 = getSingleOutputEvent(f, event1);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "error")
        .put("values", LDValue.buildObject()
            .put("old", true)
            .build())
        .build(), received1.get("measurements").get(1));

    Event.MigrationOp event2 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        null,
        new Event.MigrationOp.ErrorMeasurement(false, true)
    );

    LDValue received2 = getSingleOutputEvent(f, event2);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "error")
        .put("values", LDValue.buildObject()
            .put("new", true)
            .build())
        .build(), received2.get("measurements").get(1));

    Event.MigrationOp event3 = new Event.MigrationOp(
        0,
        context,
        "migration-key",
        1,
        2,
        LDValue.of("live"),
        LDValue.of("off"),
        EvaluationReason.fallthrough(false),
        2,
        "read",
        new Event.MigrationOp.InvokedMeasurement(false, true),
        null,
        null,
        new Event.MigrationOp.ErrorMeasurement(true, true)
    );

    LDValue received3 = getSingleOutputEvent(f, event3);
    assertJsonEquals(LDValue.buildObject()
        .put("key", "error")
        .put("values", LDValue.buildObject()
            .put("old", true)
            .put("new", true)
            .build())
        .build(), received3.get("measurements").get(1));
  }

  @Test
  public void unknownEventClassIsNotSerialized() throws Exception {
    // This shouldn't be able to happen in reality.
    Event event = new FakeEventClass(1000, LDContext.create("user"));

    EventOutputFormatter f = new EventOutputFormatter(defaultEventsConfig());
    StringWriter w = new StringWriter();
    f.writeOutputEvents(new Event[] { event }, Collections.singletonList(new EventSummary()), w);

    assertEquals("[]", w.toString());
  }

  private static class FakeEventClass extends Event {
    public FakeEventClass(long creationDate, LDContext context) {
      super(creationDate, context);
    }
  }

  private static LDValue parseValue(String json) {
    return gson.fromJson(json, LDValue.class);
  }

  private LDValue getSingleOutputEvent(EventOutputFormatter f, Event event) throws IOException {
    StringWriter w = new StringWriter();
    int count = f.writeOutputEvents(new Event[] { event }, Collections.singletonList(new EventSummary()), w);
    assertEquals(1, count);
    return parseValue(w.toString()).get(0);
  }

  private void testInlineContextSerialization(LDContext context, LDValue expectedJsonValue, EventsConfiguration baseConfig) throws IOException {
    EventsConfiguration config = makeEventsConfig(baseConfig.allAttributesPrivate, baseConfig.privateAttributes);
    EventOutputFormatter f = new EventOutputFormatter(config);

    Event.Custom customEvent = customEvent(context, FLAG_KEY).build();
    LDValue outputEvent = getSingleOutputEvent(f, customEvent);
    assertJsonEquals(LDValue.ofNull(), outputEvent.get("contextKeys"));
    assertJsonEquals(expectedJsonValue, outputEvent.get("context"));

    Event.FeatureRequest featureEvent = featureEvent(context, FLAG_KEY).build();
    outputEvent = getSingleOutputEvent(f, featureEvent);
    assertJsonEquals(LDValue.ofNull(), outputEvent.get("contextKeys"));
    assertJsonEquals(expectedJsonValue, outputEvent.get("context"));

    Event.Identify identifyEvent = identifyEvent(context);
    outputEvent = getSingleOutputEvent(f, identifyEvent);
    assertJsonEquals(LDValue.ofNull(), outputEvent.get("contextKeys"));
    assertJsonEquals(expectedJsonValue, outputEvent.get("context"));

    Event.Index indexEvent = new Event.Index(0, context);
    outputEvent = getSingleOutputEvent(f, indexEvent);
    assertJsonEquals(LDValue.ofNull(), outputEvent.get("contextKeys"));
    assertJsonEquals(expectedJsonValue, outputEvent.get("context"));
  }
}
