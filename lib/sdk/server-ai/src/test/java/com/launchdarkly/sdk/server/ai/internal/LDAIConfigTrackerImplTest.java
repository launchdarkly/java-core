package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class LDAIConfigTrackerImplTest {
  private LDClientInterface client;
  private LDLogger logger;
  private final LDContext context = LDContext.create("user-key");
  private final List<Event> events = new CopyOnWriteArrayList<>();

  private static final class Event {
    final String name;
    final LDValue data;
    final double metric;

    Event(String name, LDValue data, double metric) {
      this.name = name;
      this.data = data;
      this.metric = metric;
    }
  }

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logger = LDLogger.withAdapter(Logs.capture(), "test");
    doAnswer(inv -> {
      events.add(new Event(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
      return null;
    }).when(client).trackMetric(anyString(), any(), any(), anyDouble());
  }

  private LDAIConfigTrackerImpl tracker() {
    return new LDAIConfigTrackerImpl(
        client, "run-1", "cfg", "v1", 3, "gpt-4", "openai", context, null, logger);
  }

  private List<Event> named(String name) {
    return events.stream().filter(e -> e.name.equals(name)).collect(Collectors.toList());
  }

  // ---- duration -------------------------------------------------------------

  @Test
  public void trackDurationEmitsOnce() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackDuration(Duration.ofMillis(120));
    t.trackDuration(Duration.ofMillis(999));
    List<Event> e = named("$ld:ai:duration:total");
    assertThat(e, hasSize(1));
    assertThat(e.get(0).metric, is(120.0));
    assertThat(e.get(0).data.get("runId").stringValue(), is("run-1"));
  }

  @Test
  public void trackDurationClampsNegative() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackDuration(Duration.ofMillis(-50));
    assertThat(named("$ld:ai:duration:total").get(0).metric, is(0.0));
  }

  @Test
  public void trackDurationOfReturnsResultAndRecordsDuration() throws Exception {
    LDAIConfigTrackerImpl t = tracker();
    String result = t.trackDurationOf(() -> "ok");
    assertThat(result, is("ok"));
    assertThat(named("$ld:ai:duration:total"), hasSize(1));
  }

  @Test
  public void trackDurationOfRecordsDurationEvenWhenOperationThrows() {
    LDAIConfigTrackerImpl t = tracker();
    assertThrows(IllegalStateException.class, () -> t.trackDurationOf(() -> {
      throw new IllegalStateException("boom");
    }));
    assertThat(named("$ld:ai:duration:total"), hasSize(1));
  }

  // ---- time to first token --------------------------------------------------

  @Test
  public void trackTimeToFirstTokenEmitsOnce() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackTimeToFirstToken(Duration.ofMillis(40));
    t.trackTimeToFirstToken(Duration.ofMillis(80));
    List<Event> e = named("$ld:ai:tokens:ttf");
    assertThat(e, hasSize(1));
    assertThat(e.get(0).metric, is(40.0));
  }

  // ---- success / error ------------------------------------------------------

  @Test
  public void successAndErrorShareAtMostOnce() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackSuccess();
    t.trackError();
    assertThat(named("$ld:ai:generation:success"), hasSize(1));
    assertThat(named("$ld:ai:generation:error"), is(empty()));
  }

  // ---- feedback -------------------------------------------------------------

  @Test
  public void trackFeedbackEmitsOnceForKind() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackFeedback(FeedbackKind.POSITIVE);
    t.trackFeedback(FeedbackKind.NEGATIVE);
    assertThat(named("$ld:ai:feedback:user:positive"), hasSize(1));
    assertThat(named("$ld:ai:feedback:user:negative"), is(empty()));
  }

  @Test
  public void trackFeedbackNullIsIgnored() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackFeedback(null);
    assertThat(events, is(empty()));
  }

  // ---- tokens ---------------------------------------------------------------

  @Test
  public void trackTokensEmitsEachPositiveComponentOnce() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackTokens(new TokenUsage(30, 10, 20));
    t.trackTokens(new TokenUsage(99, 99, 99));
    assertThat(named("$ld:ai:tokens:total"), hasSize(1));
    assertThat(named("$ld:ai:tokens:total").get(0).metric, is(30.0));
    assertThat(named("$ld:ai:tokens:input").get(0).metric, is(10.0));
    assertThat(named("$ld:ai:tokens:output").get(0).metric, is(20.0));
  }

  @Test
  public void trackTokensSkipsZeroAndClampsNegative() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackTokens(new TokenUsage(0, -5, 7));
    assertThat(named("$ld:ai:tokens:total"), is(empty()));
    assertThat(named("$ld:ai:tokens:input"), is(empty()));
    assertThat(named("$ld:ai:tokens:output").get(0).metric, is(7.0));
  }

  // ---- tool calls -----------------------------------------------------------

  @Test
  public void trackToolCallEmitsEachTimeWithToolKey() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackToolCall("search");
    t.trackToolCall("search");
    List<Event> e = named("$ld:ai:tool_call");
    assertThat(e, hasSize(2));
    assertThat(e.get(0).data.get("toolKey").stringValue(), is("search"));
  }

  @Test
  public void trackToolCallsRecordsAll() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackToolCalls(Arrays.asList("a", "b", "c"));
    assertThat(named("$ld:ai:tool_call"), hasSize(3));
    assertThat(t.getSummary().getToolCalls(), contains("a", "b", "c"));
  }

  @Test
  public void trackToolCallNullIsIgnored() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackToolCall(null);
    assertThat(events, is(empty()));
  }

  // ---- judge result ---------------------------------------------------------

  @Test
  public void trackJudgeResultEmitsScoreAgainstMetricKey() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackJudgeResult(JudgeResult.builder(true, true)
        .metricKey("relevance").score(0.75).judgeConfigKey("judge-1").build());
    List<Event> e = named("relevance");
    assertThat(e, hasSize(1));
    assertThat(e.get(0).metric, is(0.75));
    assertThat(e.get(0).data.get("judgeConfigKey").stringValue(), is("judge-1"));
  }

  @Test
  public void trackJudgeResultEmitsForLegitimateZeroScore() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackJudgeResult(JudgeResult.builder(true, true).metricKey("relevance").score(0.0).build());
    assertThat(named("relevance"), hasSize(1));
    assertThat(named("relevance").get(0).metric, is(0.0));
  }

  @Test
  public void trackJudgeResultSkippedWhenNotSampledOrNoScore() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackJudgeResult(JudgeResult.builder(false, true).metricKey("relevance").score(0.9).build());
    t.trackJudgeResult(JudgeResult.builder(true, true).metricKey("relevance").build());
    assertThat(events, is(empty()));
  }

  // ---- trackMetricsOf -------------------------------------------------------

  @Test
  public void trackMetricsOfRecordsOutcomeTokensAndToolCalls() throws Exception {
    LDAIConfigTrackerImpl t = tracker();
    String result = t.trackMetricsOf(
        r -> Metrics.builder(true)
            .tokens(new TokenUsage(15, 5, 10))
            .toolCalls(Arrays.asList("x"))
            .build(),
        () -> "answer");
    assertThat(result, is("answer"));
    assertThat(named("$ld:ai:duration:total"), hasSize(1));
    assertThat(named("$ld:ai:generation:success"), hasSize(1));
    assertThat(named("$ld:ai:tokens:total"), hasSize(1));
    assertThat(named("$ld:ai:tool_call"), hasSize(1));
  }

  @Test
  public void trackMetricsOfRecordsErrorAndRethrowsWhenOperationThrows() {
    LDAIConfigTrackerImpl t = tracker();
    assertThrows(IllegalStateException.class, () -> t.trackMetricsOf(
        r -> Metrics.builder(true).build(),
        () -> {
          throw new IllegalStateException("op failed");
        }));
    assertThat(named("$ld:ai:generation:error"), hasSize(1));
    assertThat(named("$ld:ai:duration:total"), hasSize(1));
  }

  @Test
  public void trackMetricsOfRecordsErrorAndRethrowsWhenExtractorThrows() {
    LDAIConfigTrackerImpl t = tracker();
    assertThrows(RuntimeException.class, () -> t.trackMetricsOf(
        r -> {
          throw new RuntimeException("extractor failed");
        },
        () -> "answer"));
    assertThat(named("$ld:ai:generation:error"), hasSize(1));
  }

  // ---- data / summary -------------------------------------------------------

  @Test
  public void getTrackDataExposesCorrelationFields() {
    TrackData d = tracker().getTrackData();
    assertThat(d.getRunId(), is("run-1"));
    assertThat(d.getConfigKey(), is("cfg"));
    assertThat(d.getVariationKey(), is("v1"));
    assertThat(d.getVersion(), is(3));
    assertThat(d.getModelName(), is("gpt-4"));
    assertThat(d.getProviderName(), is("openai"));
  }

  @Test
  public void resumptionTokenRoundTrips() {
    LDAIConfigTrackerImpl t = tracker();
    LDAIConfigTrackerImpl restored =
        LDAIConfigTrackerImpl.fromResumptionToken(t.getResumptionToken(), client, context, logger);
    assertThat(restored.getTrackData().getRunId(), is("run-1"));
    assertThat(restored.getTrackData().getConfigKey(), is("cfg"));
    assertThat(restored.getTrackData().getVariationKey(), is("v1"));
    assertThat(restored.getTrackData().getVersion(), is(3));
    // Model and provider names are not carried in the token.
    assertThat(restored.getTrackData().getModelName(), is(""));
    assertThat(restored.getTrackData().getProviderName(), is(""));
  }

  @Test
  public void summaryReflectsRecordedMetrics() {
    LDAIConfigTrackerImpl t = tracker();
    t.trackDuration(Duration.ofMillis(100));
    t.trackSuccess();
    t.trackTokens(new TokenUsage(20, 8, 12));
    t.trackFeedback(FeedbackKind.POSITIVE);
    MetricSummary s = t.getSummary();
    assertThat(s.getDurationMs(), is(100L));
    assertThat(s.getSuccess(), is(true));
    assertThat(s.getTokens(), is(new TokenUsage(20, 8, 12)));
    assertThat(s.getFeedback(), is(FeedbackKind.POSITIVE));
    assertThat(s.getResumptionToken(), is(t.getResumptionToken()));
  }

  // ---- concurrency ----------------------------------------------------------

  @Test
  public void concurrentOutcomeRecordsExactlyOneEvent() throws Exception {
    LDAIConfigTrackerImpl t = tracker();
    runConcurrently(32, i -> {
      if (i % 2 == 0) {
        t.trackSuccess();
      } else {
        t.trackError();
      }
    });
    int outcomes = named("$ld:ai:generation:success").size() + named("$ld:ai:generation:error").size();
    assertThat(outcomes, is(1));
  }

  @Test
  public void concurrentDurationRecordsExactlyOnce() throws Exception {
    LDAIConfigTrackerImpl t = tracker();
    runConcurrently(32, i -> t.trackDuration(Duration.ofMillis(10 + i)));
    assertThat(named("$ld:ai:duration:total"), hasSize(1));
  }

  @Test
  public void concurrentToolCallsRecordAllWithIntactList() throws Exception {
    LDAIConfigTrackerImpl t = tracker();
    runConcurrently(50, i -> t.trackToolCall("tool-" + i));
    assertThat(named("$ld:ai:tool_call"), hasSize(50));
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      expected.add("tool-" + i);
    }
    assertThat(t.getSummary().getToolCalls(), containsInAnyOrder(expected.toArray()));
  }

  private interface IndexedTask {
    void run(int index);
  }

  private static void runConcurrently(int threads, IndexedTask task) throws InterruptedException {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger failures = new AtomicInteger(0);
    for (int i = 0; i < threads; i++) {
      final int index = i;
      pool.execute(() -> {
        ready.countDown();
        try {
          go.await();
          task.run(index);
        } catch (Throwable t) {
          failures.incrementAndGet();
        } finally {
          done.countDown();
        }
      });
    }
    ready.await();
    go.countDown();
    done.await(10, TimeUnit.SECONDS);
    pool.shutdownNow();
    assertThat(failures.get(), is(0));
  }
}
