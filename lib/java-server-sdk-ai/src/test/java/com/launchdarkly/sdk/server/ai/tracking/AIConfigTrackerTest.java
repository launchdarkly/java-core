package com.launchdarkly.sdk.server.ai.tracking;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.MockLDClient;
import com.launchdarkly.sdk.server.ai.evaluation.JudgeResult;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AIConfigTrackerTest {
  private MockLDClient client;
  private LDContext context;
  private AIConfigTracker tracker;

  @Before
  public void setUp() {
    client = new MockLDClient();
    context = LDContext.create("user-key");
    tracker = AIConfigTracker.builder(client)
        .runId("run-1")
        .configKey("my-config")
        .variationKey("var-1")
        .version(7)
        .context(context)
        .modelName("gpt-4")
        .providerName("openai")
        .build();
  }

  private MockLDClient.TrackEvent single(String eventName) {
    assertEquals(1, client.eventsNamed(eventName).size());
    return client.eventsNamed(eventName).get(0);
  }

  @Test
  public void trackDurationEmitsEventWithMetadata() {
    tracker.trackDuration(1234);
    MockLDClient.TrackEvent event = single("$ld:ai:duration:total");
    assertEquals(1234.0, event.metricValue, 0.0);
    assertEquals("run-1", event.data.get("runId").stringValue());
    assertEquals("my-config", event.data.get("configKey").stringValue());
    assertEquals(7, event.data.get("version").intValue());
    assertEquals("gpt-4", event.data.get("modelName").stringValue());
    assertEquals("openai", event.data.get("providerName").stringValue());
    assertEquals("var-1", event.data.get("variationKey").stringValue());
    assertEquals(Long.valueOf(1234), tracker.getSummary().getDurationMs());
  }

  @Test
  public void durationIsRecordedAtMostOnce() {
    tracker.trackDuration(100);
    tracker.trackDuration(200);
    assertEquals(1, client.eventsNamed("$ld:ai:duration:total").size());
    assertEquals(Long.valueOf(100), tracker.getSummary().getDurationMs());
  }

  @Test
  public void trackDurationOfReturnsValueAndRecordsDuration() {
    String result = tracker.trackDurationOf(() -> "answer");
    assertEquals("answer", result);
    assertEquals(1, client.eventsNamed("$ld:ai:duration:total").size());
  }

  @Test
  public void trackDurationOfRecordsDurationEvenOnException() {
    try {
      tracker.trackDurationOf(() -> {
        throw new RuntimeException("boom");
      });
    } catch (RuntimeException ignored) {
      // expected
    }
    assertEquals(1, client.eventsNamed("$ld:ai:duration:total").size());
  }

  @Test
  public void successAndErrorShareAtMostOnceState() {
    tracker.trackSuccess();
    tracker.trackError();
    assertEquals(1, client.eventsNamed("$ld:ai:generation:success").size());
    assertEquals(0, client.eventsNamed("$ld:ai:generation:error").size());
    assertEquals(Boolean.TRUE, tracker.getSummary().getSuccess());
  }

  @Test
  public void trackFeedbackPositive() {
    tracker.trackFeedback(FeedbackKind.POSITIVE);
    assertEquals(1, client.eventsNamed("$ld:ai:feedback:user:positive").size());
    assertEquals(FeedbackKind.POSITIVE, tracker.getSummary().getFeedback());
  }

  @Test
  public void trackFeedbackNegative() {
    tracker.trackFeedback(FeedbackKind.NEGATIVE);
    assertEquals(1, client.eventsNamed("$ld:ai:feedback:user:negative").size());
  }

  @Test
  public void trackTokensOnlyEmitsPositiveCounts() {
    tracker.trackTokens(new TokenUsage(10, 0, 6));
    assertEquals(1, client.eventsNamed("$ld:ai:tokens:total").size());
    assertEquals(0, client.eventsNamed("$ld:ai:tokens:input").size());
    assertEquals(1, client.eventsNamed("$ld:ai:tokens:output").size());
    assertEquals(10.0, single("$ld:ai:tokens:total").metricValue, 0.0);
    assertEquals(6.0, single("$ld:ai:tokens:output").metricValue, 0.0);
  }

  @Test
  public void trackTimeToFirstToken() {
    tracker.trackTimeToFirstToken(42);
    assertEquals(42.0, single("$ld:ai:tokens:ttf").metricValue, 0.0);
    assertEquals(Long.valueOf(42), tracker.getSummary().getTimeToFirstTokenMs());
  }

  @Test
  public void trackToolCallsEmitsEventPerToolWithToolKey() {
    tracker.trackToolCalls(Arrays.asList("search", "weather"));
    assertEquals(2, client.eventsNamed("$ld:ai:tool_call").size());
    assertEquals("search", client.eventsNamed("$ld:ai:tool_call").get(0).data.get("toolKey").stringValue());
    assertEquals(Arrays.asList("search", "weather"), tracker.getSummary().getToolCalls());
  }

  @Test
  public void trackJudgeResultEmitsScoredEvent() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true)
        .success(true)
        .metricKey("$ld:ai:judge:relevance")
        .judgeConfigKey("relevance-judge")
        .score(0.9)
        .build();
    tracker.trackJudgeResult(result);
    MockLDClient.TrackEvent event = single("$ld:ai:judge:relevance");
    assertEquals(0.9, event.metricValue, 0.0);
    assertEquals("relevance-judge", event.data.get("judgeConfigKey").stringValue());
  }

  @Test
  public void trackJudgeResultIgnoresUnsampledOrFailed() {
    tracker.trackJudgeResult(JudgeResult.notSampled());
    tracker.trackJudgeResult(JudgeResult.builder().sampled(true).success(false)
        .metricKey("$ld:ai:judge:relevance").build());
    assertTrue(client.eventsNamed("$ld:ai:judge:relevance").isEmpty());
  }

  @Test
  public void trackMetricsOfRecordsDurationSuccessAndTokens() {
    String result = tracker.trackMetricsOf(
        value -> AIMetrics.builder(true).tokens(new TokenUsage(10, 4, 6)).build(),
        () -> "ok");
    assertEquals("ok", result);
    assertEquals(1, client.eventsNamed("$ld:ai:duration:total").size());
    assertEquals(1, client.eventsNamed("$ld:ai:generation:success").size());
    assertEquals(1, client.eventsNamed("$ld:ai:tokens:total").size());
  }

  @Test
  public void trackMetricsOfUsesProvidedDuration() {
    tracker.trackMetricsOf(
        value -> AIMetrics.builder(true).durationMs(555L).build(),
        () -> "ok");
    assertEquals(555.0, single("$ld:ai:duration:total").metricValue, 0.0);
  }

  @Test
  public void trackMetricsOfRecordsErrorAndRethrowsOnException() {
    try {
      tracker.trackMetricsOf(value -> AIMetrics.builder(true).build(), () -> {
        throw new IllegalStateException("boom");
      });
    } catch (IllegalStateException expected) {
      assertEquals("boom", expected.getMessage());
    }
    assertEquals(1, client.eventsNamed("$ld:ai:duration:total").size());
    assertEquals(1, client.eventsNamed("$ld:ai:generation:error").size());
  }

  @Test
  public void summaryCarriesResumptionTokenAtConstruction() {
    assertEquals(tracker.getResumptionToken(), tracker.getSummary().getResumptionToken());
  }

  @Test
  public void fromResumptionTokenPreservesRunIdentity() {
    String token = tracker.getResumptionToken();
    AIConfigTracker restored = AIConfigTracker.fromResumptionToken(token, client, context);
    restored.trackSuccess();

    MockLDClient.TrackEvent event = single("$ld:ai:generation:success");
    assertEquals("run-1", event.data.get("runId").stringValue());
    assertEquals("my-config", event.data.get("configKey").stringValue());
    assertEquals(7, event.data.get("version").intValue());
    assertEquals("var-1", event.data.get("variationKey").stringValue());
    // Model and provider names are not carried in the token.
    assertEquals("", event.data.get("modelName").stringValue());
    assertEquals("", event.data.get("providerName").stringValue());
  }

  @Test
  public void graphKeyIsIncludedWhenSet() {
    AIConfigTracker graphTracker = AIConfigTracker.builder(client)
        .runId("run-2")
        .configKey("node-config")
        .version(1)
        .context(context)
        .graphKey("graph-1")
        .build();
    graphTracker.trackSuccess();
    assertEquals("graph-1", single("$ld:ai:generation:success").data.get("graphKey").stringValue());
  }

  @Test
  public void omitsEmptyVariationKeyFromTrackData() {
    AIConfigTracker noVariation = AIConfigTracker.builder(client)
        .runId("run-3")
        .configKey("c")
        .version(1)
        .context(context)
        .build();
    noVariation.trackSuccess();
    assertTrue(single("$ld:ai:generation:success").data.get("variationKey").isNull());
  }
}
