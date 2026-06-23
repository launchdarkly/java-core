package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("javadoc")
public class LDAIConfigTrackerImplTest {
  private LDClientInterface client;
  private LogCapture logCapture;
  private LDLogger logger;
  private LDAIConfigTrackerImpl tracker;

  private static final LDContext CONTEXT = LDContext.create("user-key");
  private static final String RUN_ID = "test-run-id";
  private static final String CONFIG_KEY = "my-config";
  private static final String VARIATION_KEY = "variation-abc";
  private static final int VERSION = 3;
  private static final String MODEL_NAME = "gpt-4";
  private static final String PROVIDER_NAME = "openai";

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logCapture = Logs.capture();
    logger = LDLogger.withAdapter(logCapture, "test");
    tracker = makeTracker(VARIATION_KEY);
  }

  private LDAIConfigTrackerImpl makeTracker(String variationKey) {
    return new LDAIConfigTrackerImpl(
        client, RUN_ID, CONFIG_KEY, variationKey, VERSION,
        MODEL_NAME, PROVIDER_NAME, CONTEXT, null, logger);
  }

  private List<String> warnings() {
    return logCapture.getMessages().stream()
        .filter(m -> m.getLevel() == LDLogLevel.WARN)
        .map(LogCapture.Message::getText)
        .collect(Collectors.toList());
  }

  private LDValue baseExpectedData() {
    return LDValue.buildObject()
        .put("runId", RUN_ID)
        .put("configKey", CONFIG_KEY)
        .put("variationKey", VARIATION_KEY)
        .put("version", VERSION)
        .put("modelName", MODEL_NAME)
        .put("providerName", PROVIDER_NAME)
        .build();
  }

  // ---- getTrackData / getResumptionToken ------------------------------------

  @Test
  public void getTrackDataReturnsCorrectFields() {
    TrackData data = tracker.getTrackData();
    assertThat(data.getRunId(), is(RUN_ID));
    assertThat(data.getConfigKey(), is(CONFIG_KEY));
    assertThat(data.getVariationKey(), is(VARIATION_KEY));
    assertThat(data.getVersion(), is(VERSION));
    assertThat(data.getModelName(), is(MODEL_NAME));
    assertThat(data.getProviderName(), is(PROVIDER_NAME));
    assertThat(data.getGraphKey(), is(nullValue()));
  }

  @Test
  public void getTrackDataOmitsVariationKeyWhenNull() {
    LDAIConfigTrackerImpl t = makeTracker(null);
    assertThat(t.getTrackData().getVariationKey(), is(nullValue()));
    LDValue ldv = t.getTrackData().toLDValue();
    assertThat(ldv.get("variationKey").isNull(), is(true)); // absent key returns LDValue.ofNull()
  }

  @Test
  public void getResumptionTokenIsNotNull() {
    assertThat(tracker.getResumptionToken(), is(notNullValue()));
  }

  @Test
  public void resumptionTokenRoundTrips() throws Exception {
    String token = tracker.getResumptionToken();
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is(RUN_ID));
    assertThat(d.getConfigKey(), is(CONFIG_KEY));
    assertThat(d.getVariationKey(), is(VARIATION_KEY));
    assertThat(d.getVersion(), is(VERSION));
    assertThat(d.getGraphKey(), is(nullValue()));
  }

  @Test
  public void fromResumptionTokenRestoresCorrectFields() {
    String token = tracker.getResumptionToken();
    LDAIConfigTrackerImpl restored =
        LDAIConfigTrackerImpl.fromResumptionToken(token, client, CONTEXT, logger);
    TrackData data = restored.getTrackData();
    assertThat(data.getRunId(), is(RUN_ID));
    assertThat(data.getConfigKey(), is(CONFIG_KEY));
    assertThat(data.getVariationKey(), is(VARIATION_KEY));
    assertThat(data.getVersion(), is(VERSION));
    assertThat(data.getModelName(), is("")); // not in token
    assertThat(data.getProviderName(), is("")); // not in token
  }

  // ---- trackDuration --------------------------------------------------------

  @Test
  public void trackDurationEmitsCorrectEvent() {
    tracker.trackDuration(Duration.ofMillis(500));
    verify(client).trackMetric(
        eq("$ld:ai:duration:total"), eq(CONTEXT), eq(baseExpectedData()), eq(500.0));
  }

  @Test
  public void trackDurationClampsNegativeToZero() {
    tracker.trackDuration(Duration.ofMillis(-100));
    verify(client).trackMetric(
        eq("$ld:ai:duration:total"), eq(CONTEXT), eq(baseExpectedData()), eq(0.0));
  }

  @Test
  public void trackDurationAtMostOnce() {
    tracker.trackDuration(Duration.ofMillis(100));
    tracker.trackDuration(Duration.ofMillis(200));
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:duration:total"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
    assertThat(warnings().get(0), containsString("duration"));
  }

  @Test
  public void trackDurationNullIsIgnoredWithWarning() {
    tracker.trackDuration(null);
    verify(client, never()).trackMetric(eq("$ld:ai:duration:total"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackDurationOf ------------------------------------------------------

  @Test
  public void trackDurationOfReturnResultAndTracksDuration() throws Exception {
    String result = tracker.trackDurationOf(() -> "hello");
    assertThat(result, is("hello"));
    verify(client, times(1)).trackMetric(eq("$ld:ai:duration:total"), any(), any(), anyDouble());
  }

  @Test
  public void trackDurationOfTracksDurationEvenOnException() {
    try {
      tracker.trackDurationOf(() -> {
        throw new RuntimeException("boom");
      });
    } catch (Exception ignored) {
    }
    verify(client, times(1)).trackMetric(eq("$ld:ai:duration:total"), any(), any(), anyDouble());
  }

  // ---- trackTimeToFirstToken ------------------------------------------------

  @Test
  public void trackTimeToFirstTokenEmitsCorrectEvent() {
    tracker.trackTimeToFirstToken(Duration.ofMillis(250));
    verify(client).trackMetric(
        eq("$ld:ai:tokens:ttf"), eq(CONTEXT), eq(baseExpectedData()), eq(250.0));
  }

  @Test
  public void trackTimeToFirstTokenAtMostOnce() {
    tracker.trackTimeToFirstToken(Duration.ofMillis(100));
    tracker.trackTimeToFirstToken(Duration.ofMillis(200));
    verify(client, times(1)).trackMetric(eq("$ld:ai:tokens:ttf"), any(), any(), anyDouble());
  }

  @Test
  public void trackTimeToFirstTokenNullIsIgnoredWithWarning() {
    tracker.trackTimeToFirstToken(null);
    verify(client, never()).trackMetric(eq("$ld:ai:tokens:ttf"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackSuccess / trackError --------------------------------------------

  @Test
  public void trackSuccessEmitsCorrectEvent() {
    tracker.trackSuccess();
    verify(client).trackMetric(
        eq("$ld:ai:generation:success"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackErrorEmitsCorrectEvent() {
    tracker.trackError();
    verify(client).trackMetric(
        eq("$ld:ai:generation:error"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackSuccessAtMostOnce() {
    tracker.trackSuccess();
    tracker.trackSuccess();
    verify(client, times(1)).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  @Test
  public void trackErrorAtMostOnce() {
    tracker.trackError();
    tracker.trackError();
    verify(client, times(1)).trackMetric(eq("$ld:ai:generation:error"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  @Test
  public void trackSuccessAndErrorShareGuard_successFirst() {
    tracker.trackSuccess();
    tracker.trackError();
    verify(client, times(1)).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(eq("$ld:ai:generation:error"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  @Test
  public void trackSuccessAndErrorShareGuard_errorFirst() {
    tracker.trackError();
    tracker.trackSuccess();
    verify(client, times(1)).trackMetric(eq("$ld:ai:generation:error"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackFeedback --------------------------------------------------------

  @Test
  public void trackFeedbackPositiveEmitsCorrectEvent() {
    tracker.trackFeedback(FeedbackKind.POSITIVE);
    verify(client).trackMetric(
        eq("$ld:ai:feedback:user:positive"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackFeedbackNegativeEmitsCorrectEvent() {
    tracker.trackFeedback(FeedbackKind.NEGATIVE);
    verify(client).trackMetric(
        eq("$ld:ai:feedback:user:negative"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackFeedbackAtMostOnce() {
    tracker.trackFeedback(FeedbackKind.POSITIVE);
    tracker.trackFeedback(FeedbackKind.NEGATIVE);
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:feedback:user:positive"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(
        eq("$ld:ai:feedback:user:negative"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  @Test
  public void trackFeedbackNullIsIgnoredWithWarning_slotNotBurned() {
    tracker.trackFeedback(null);
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
    // Slot should not be burned — a subsequent valid call should still work
    tracker.trackFeedback(FeedbackKind.POSITIVE);
    verify(client, times(1)).trackMetric(eq("$ld:ai:feedback:user:positive"), any(), any(), anyDouble());
  }

  // ---- trackTokens ----------------------------------------------------------

  @Test
  public void trackTokensEmitsEventsForPositiveCounts() {
    tracker.trackTokens(new TokenUsage(100, 60, 40));
    verify(client).trackMetric(eq("$ld:ai:tokens:total"), eq(CONTEXT), eq(baseExpectedData()), eq(100.0));
    verify(client).trackMetric(eq("$ld:ai:tokens:input"), eq(CONTEXT), eq(baseExpectedData()), eq(60.0));
    verify(client).trackMetric(eq("$ld:ai:tokens:output"), eq(CONTEXT), eq(baseExpectedData()), eq(40.0));
  }

  @Test
  public void trackTokensSkipsZeroCounts() {
    tracker.trackTokens(new TokenUsage(0, 0, 40));
    verify(client, never()).trackMetric(eq("$ld:ai:tokens:total"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(eq("$ld:ai:tokens:input"), any(), any(), anyDouble());
    verify(client).trackMetric(eq("$ld:ai:tokens:output"), any(), any(), eq(40.0));
  }

  @Test
  public void trackTokensAllZeroDoesNotBurnSlot() {
    tracker.trackTokens(new TokenUsage(0, 0, 0));
    // Slot not burned — next valid call should succeed
    tracker.trackTokens(new TokenUsage(10, 5, 5));
    verify(client).trackMetric(eq("$ld:ai:tokens:total"), any(), any(), eq(10.0));
  }

  @Test
  public void trackTokensAtMostOnce() {
    tracker.trackTokens(new TokenUsage(10, 5, 5));
    tracker.trackTokens(new TokenUsage(20, 10, 10));
    verify(client, times(1)).trackMetric(eq("$ld:ai:tokens:total"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  @Test
  public void trackTokensNullIsIgnoredWithWarning() {
    tracker.trackTokens(null);
    verify(client, never()).trackMetric(eq("$ld:ai:tokens:total"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackToolCall --------------------------------------------------------

  @Test
  public void trackToolCallEmitsOnEveryCall() {
    LDValue expectedDataWithTool = LDValue.buildObject()
        .put("runId", RUN_ID).put("configKey", CONFIG_KEY)
        .put("variationKey", VARIATION_KEY).put("version", VERSION)
        .put("modelName", MODEL_NAME).put("providerName", PROVIDER_NAME)
        .put("toolKey", "search")
        .build();

    tracker.trackToolCall("search");
    tracker.trackToolCall("search");
    tracker.trackToolCall("fetch");

    verify(client, times(2)).trackMetric(
        eq("$ld:ai:tool_call"), eq(CONTEXT), eq(expectedDataWithTool), eq(1.0));
    LDValue fetchData = LDValue.buildObject()
        .put("runId", RUN_ID).put("configKey", CONFIG_KEY)
        .put("variationKey", VARIATION_KEY).put("version", VERSION)
        .put("modelName", MODEL_NAME).put("providerName", PROVIDER_NAME)
        .put("toolKey", "fetch")
        .build();
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:tool_call"), eq(CONTEXT), eq(fetchData), eq(1.0));
  }

  @Test
  public void trackToolCallsDelegate() {
    tracker.trackToolCalls(Arrays.asList("a", "b"));
    verify(client, times(2)).trackMetric(eq("$ld:ai:tool_call"), any(), any(), anyDouble());
  }

  @Test
  public void trackToolCallNullIsIgnoredWithWarning() {
    tracker.trackToolCall(null);
    verify(client, never()).trackMetric(eq("$ld:ai:tool_call"), any(), any(), anyDouble());
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackJudgeResult -----------------------------------------------------

  @Test
  public void trackJudgeResultEmitsWhenSampledAndSucceeded() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(true)
        .metricKey("judge-score").score(0.85)
        .judgeConfigKey("my-judge")
        .build();

    LDValue expectedData = LDValue.buildObject()
        .put("runId", RUN_ID).put("configKey", CONFIG_KEY)
        .put("variationKey", VARIATION_KEY).put("version", VERSION)
        .put("modelName", MODEL_NAME).put("providerName", PROVIDER_NAME)
        .put("judgeConfigKey", "my-judge")
        .build();

    tracker.trackJudgeResult(result);
    verify(client).trackMetric(eq("judge-score"), eq(CONTEXT), eq(expectedData), eq(0.85));
  }

  @Test
  public void trackJudgeResultSkipsWhenNotSampled() {
    JudgeResult result = JudgeResult.builder()
        .sampled(false).success(true).metricKey("k").score(1.0).build();
    tracker.trackJudgeResult(result);
    verify(client, never()).trackMetric(eq("k"), any(), any(), anyDouble());
  }

  @Test
  public void trackJudgeResultSkipsWhenNotSuccess() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(false).metricKey("k").score(1.0).build();
    tracker.trackJudgeResult(result);
    verify(client, never()).trackMetric(eq("k"), any(), any(), anyDouble());
  }

  @Test
  public void trackJudgeResultSkipsWhenMetricKeyNull() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(true).metricKey(null).score(1.0).build();
    tracker.trackJudgeResult(result);
    verify(client, never()).trackMetric(any(), any(), any(), anyDouble());
  }

  @Test
  public void trackJudgeResultSkipsWhenScoreNull() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(true).metricKey("k").score(null).build();
    tracker.trackJudgeResult(result);
    verify(client, never()).trackMetric(eq("k"), any(), any(), anyDouble());
  }

  @Test
  public void trackJudgeResultFiresWhenScoreIsZero() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(true).metricKey("k").score(0.0).build();
    tracker.trackJudgeResult(result);
    verify(client).trackMetric(eq("k"), any(), any(), eq(0.0));
  }

  @Test
  public void trackJudgeResultOmitsJudgeConfigKeyWhenNull() {
    JudgeResult result = JudgeResult.builder()
        .sampled(true).success(true).metricKey("k").score(1.0).judgeConfigKey(null).build();
    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    tracker.trackJudgeResult(result);
    verify(client).trackMetric(eq("k"), any(), dataCaptor.capture(), anyDouble());
    assertThat(dataCaptor.getValue().get("judgeConfigKey").isNull(), is(true));
  }

  @Test
  public void trackJudgeResultIsNotAtMostOnce() {
    JudgeResult r1 = JudgeResult.builder().sampled(true).success(true).metricKey("k1").score(1.0).build();
    JudgeResult r2 = JudgeResult.builder().sampled(true).success(true).metricKey("k2").score(2.0).build();
    tracker.trackJudgeResult(r1);
    tracker.trackJudgeResult(r2);
    verify(client).trackMetric(eq("k1"), any(), any(), eq(1.0));
    verify(client).trackMetric(eq("k2"), any(), any(), eq(2.0));
  }

  @Test
  public void trackJudgeResultNullIsIgnoredWithWarning() {
    tracker.trackJudgeResult(null);
    assertThat(warnings().size(), greaterThanOrEqualTo(1));
  }

  // ---- trackMetricsOf -------------------------------------------------------

  @Test
  public void trackMetricsOfTracksSuccessAndDurationAndTokens() throws Exception {
    AIMetrics metrics = AIMetrics.builder()
        .success(true)
        .tokens(new TokenUsage(10, 6, 4))
        .build();

    String result = tracker.trackMetricsOf(r -> metrics, () -> "ok");
    assertThat(result, is("ok"));

    verify(client).trackMetric(eq("$ld:ai:generation:success"), any(), any(), eq(1.0));
    verify(client).trackMetric(eq("$ld:ai:duration:total"), any(), any(), anyDouble());
    verify(client).trackMetric(eq("$ld:ai:tokens:total"), any(), any(), eq(10.0));
    verify(client).trackMetric(eq("$ld:ai:tokens:input"), any(), any(), eq(6.0));
    verify(client).trackMetric(eq("$ld:ai:tokens:output"), any(), any(), eq(4.0));
  }

  @Test
  public void trackMetricsOfUsesRunnerReportedDurationWhenPresent() throws Exception {
    AIMetrics metrics = AIMetrics.builder().success(true).durationMs(999L).build();
    tracker.trackMetricsOf(r -> metrics, () -> "ok");
    verify(client).trackMetric(eq("$ld:ai:duration:total"), any(), any(), eq(999.0));
  }

  @Test
  public void trackMetricsOfWallClockDurationExcludesSlowExtractor() throws Exception {
    // Operation returns immediately; extractor sleeps. Recorded duration must reflect only the
    // operation, not the extractor work.
    long extractorSleepMs = 200L;
    AIMetrics metrics = AIMetrics.builder().success(true).build();
    tracker.trackMetricsOf(
        r -> { try { Thread.sleep(extractorSleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return metrics; },
        () -> "ok");
    ArgumentCaptor<Double> durationCaptor = ArgumentCaptor.forClass(Double.class);
    verify(client).trackMetric(eq("$ld:ai:duration:total"), any(), any(), durationCaptor.capture());
    assertThat(
        "wall-clock duration must not include extractor time",
        durationCaptor.getValue() < (double) extractorSleepMs / 2,
        is(true));
  }

  @Test
  public void trackMetricsOfTracksErrorAndRethrowsOnOperationException() {
    try {
      tracker.trackMetricsOf(
          r -> AIMetrics.builder().success(true).build(),
          () -> { throw new RuntimeException("ai failed"); });
    } catch (Exception e) {
      assertThat(e.getMessage(), is("ai failed"));
    }
    verify(client).trackMetric(eq("$ld:ai:generation:error"), any(), any(), eq(1.0));
    verify(client).trackMetric(eq("$ld:ai:duration:total"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
  }

  @Test
  public void trackMetricsOfExtractorExceptionPropagatesAndDoesNotCallTrackError() {
    try {
      tracker.trackMetricsOf(
          r -> { throw new RuntimeException("extractor failed"); },
          () -> "ok");
    } catch (Exception e) {
      assertThat(e.getMessage(), is("extractor failed"));
    }
    verify(client, never()).trackMetric(eq("$ld:ai:generation:error"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
  }

  @Test
  public void trackMetricsOfTracksToolCalls() throws Exception {
    AIMetrics metrics = AIMetrics.builder()
        .success(true)
        .toolCalls(Arrays.asList("search", "fetch"))
        .build();
    tracker.trackMetricsOf(r -> metrics, () -> "ok");
    verify(client, times(2)).trackMetric(eq("$ld:ai:tool_call"), any(), any(), eq(1.0));
  }

  // ---- getSummary -----------------------------------------------------------

  @Test
  public void getSummaryReturnsNullsBeforeAnyTracking() {
    MetricSummary summary = tracker.getSummary();
    assertThat(summary.getSuccess(), is(nullValue()));
    assertThat(summary.getDurationMs(), is(nullValue()));
    assertThat(summary.getTokens(), is(nullValue()));
    assertThat(summary.getFeedback(), is(nullValue()));
    assertThat(summary.getTimeToFirstTokenMs(), is(nullValue()));
    assertThat(summary.getToolCalls(), is(nullValue()));
    assertThat(summary.getResumptionToken(), is(notNullValue()));
  }

  @Test
  public void getSummaryReflectsAllTrackedValues() {
    tracker.trackDuration(Duration.ofMillis(300));
    tracker.trackTimeToFirstToken(Duration.ofMillis(50));
    tracker.trackSuccess();
    tracker.trackFeedback(FeedbackKind.POSITIVE);
    tracker.trackTokens(new TokenUsage(30, 20, 10));
    tracker.trackToolCall("search");
    tracker.trackToolCall("fetch");

    MetricSummary summary = tracker.getSummary();
    assertThat(summary.getSuccess(), is(Boolean.TRUE));
    assertThat(summary.getDurationMs(), is(300L));
    assertThat(summary.getTimeToFirstTokenMs(), is(50L));
    assertThat(summary.getFeedback(), is(FeedbackKind.POSITIVE));
    assertThat(summary.getTokens().getTotal(), is(30L));
    assertThat(summary.getToolCalls(), containsInAnyOrder("search", "fetch"));
    assertThat(summary.getResumptionToken(), is(tracker.getResumptionToken()));
  }

  @Test
  public void getSummarySuccessIsFalseWhenErrorTracked() {
    tracker.trackError();
    assertThat(tracker.getSummary().getSuccess(), is(Boolean.FALSE));
  }

  @Test
  public void getSummaryToolCallsIsImmutableSnapshot() {
    tracker.trackToolCall("a");
    List<String> snapshot1 = tracker.getSummary().getToolCalls();
    tracker.trackToolCall("b");
    List<String> snapshot2 = tracker.getSummary().getToolCalls();
    assertThat(snapshot1.size(), is(1));
    assertThat(snapshot2.size(), is(2));
  }

  // ---- variationKey omission ------------------------------------------------

  @Test
  public void variationKeyOmittedFromPayloadWhenNull() {
    LDAIConfigTrackerImpl t = makeTracker(null);
    t.trackSuccess();
    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:generation:success"), any(), dataCaptor.capture(), anyDouble());
    assertThat(dataCaptor.getValue().get("variationKey").isNull(), is(true));
  }

  @Test
  public void variationKeyIncludedInPayloadWhenPresent() {
    tracker.trackSuccess();
    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:generation:success"), any(), dataCaptor.capture(), anyDouble());
    assertThat(dataCaptor.getValue().get("variationKey").stringValue(), is(VARIATION_KEY));
  }

  // ---- graphKey inclusion ---------------------------------------------------

  @Test
  public void graphKeyIncludedInPayloadWhenSet() {
    LDAIConfigTrackerImpl t = new LDAIConfigTrackerImpl(
        client, RUN_ID, CONFIG_KEY, VARIATION_KEY, VERSION,
        MODEL_NAME, PROVIDER_NAME, CONTEXT, "my-graph", logger);
    t.trackSuccess();
    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:generation:success"), any(), dataCaptor.capture(), anyDouble());
    assertThat(dataCaptor.getValue().get("graphKey").stringValue(), is("my-graph"));
  }

  @Test
  public void graphKeyOmittedFromPayloadWhenNull() {
    tracker.trackSuccess();
    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:generation:success"), any(), dataCaptor.capture(), anyDouble());
    assertThat(dataCaptor.getValue().get("graphKey").isNull(), is(true));
  }

  // ---- concurrency: at-most-once under contention ---------------------------

  @Test
  public void trackDurationAtMostOnceUnderConcurrency() throws InterruptedException {
    int threads = 20;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger callCount = new AtomicInteger(0);
    ExecutorService exec = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      exec.submit(() -> {
        ready.countDown();
        try { go.await(); } catch (InterruptedException ignored) {}
        tracker.trackDuration(Duration.ofMillis(100));
      });
    }

    ready.await();
    go.countDown();
    exec.shutdown();
    exec.awaitTermination(5, TimeUnit.SECONDS);

    ArgumentCaptor<Double> valueCaptor = ArgumentCaptor.forClass(Double.class);
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:duration:total"), any(), any(), valueCaptor.capture());
  }

  @Test
  public void trackSuccessAtMostOnceUnderConcurrency() throws InterruptedException {
    int threads = 20;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService exec = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      exec.submit(() -> {
        ready.countDown();
        try { go.await(); } catch (InterruptedException ignored) {}
        tracker.trackSuccess();
      });
    }

    ready.await();
    go.countDown();
    exec.shutdown();
    exec.awaitTermination(5, TimeUnit.SECONDS);

    verify(client, times(1)).trackMetric(eq("$ld:ai:generation:success"), any(), any(), anyDouble());
  }

  // ---- constructor null checks ----------------------------------------------

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullClient() {
    new LDAIConfigTrackerImpl(null, RUN_ID, CONFIG_KEY, VARIATION_KEY, VERSION,
        MODEL_NAME, PROVIDER_NAME, CONTEXT, null, logger);
  }

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullContext() {
    new LDAIConfigTrackerImpl(client, RUN_ID, CONFIG_KEY, VARIATION_KEY, VERSION,
        MODEL_NAME, PROVIDER_NAME, null, null, logger);
  }
}
