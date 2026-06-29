package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("javadoc")
public class AIGraphTrackerTest {
  private LDClientInterface client;
  private LogCapture logCapture;
  private LDLogger logger;
  private AIGraphTracker tracker;

  private static final LDContext CONTEXT = LDContext.create("user-key");
  private static final String RUN_ID = "test-run-id";
  private static final String GRAPH_KEY = "my-graph";
  private static final String VARIATION_KEY = "var-abc";
  private static final int VERSION = 2;

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logCapture = Logs.capture();
    logger = LDLogger.withAdapter(logCapture, "test");
    tracker = makeTracker(VARIATION_KEY);
  }

  private AIGraphTracker makeTracker(String variationKey) {
    return new AIGraphTracker(client, RUN_ID, GRAPH_KEY, variationKey, VERSION, CONTEXT, logger);
  }

  private List<String> warnings() {
    return logCapture.getMessages().stream()
        .filter(m -> m.getLevel() == LDLogLevel.WARN)
        .map(LogCapture.Message::getText)
        .collect(Collectors.toList());
  }

  private List<String> debugs() {
    return logCapture.getMessages().stream()
        .filter(m -> m.getLevel() == LDLogLevel.DEBUG)
        .map(LogCapture.Message::getText)
        .collect(Collectors.toList());
  }

  private LDValue baseExpectedData() {
    return LDValue.buildObject()
        .put("runId", RUN_ID)
        .put("graphKey", GRAPH_KEY)
        .put("variationKey", VARIATION_KEY)
        .put("version", VERSION)
        .build();
  }

  // ---- trackInvocationSuccess -----------------------------------------------

  @Test
  public void trackInvocationSuccessEmitsCorrectEvent() {
    tracker.trackInvocationSuccess();
    verify(client).trackMetric(
        eq("$ld:ai:graph:invocation_success"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackInvocationSuccessIsAtMostOnce() {
    tracker.trackInvocationSuccess();
    tracker.trackInvocationSuccess();
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:invocation_success"), any(), any(), anyDouble());
    assertThat(warnings().stream().anyMatch(w -> w.contains("invocation already recorded")), is(true));
  }

  // ---- trackInvocationFailure -----------------------------------------------

  @Test
  public void trackInvocationFailureEmitsCorrectEvent() {
    tracker.trackInvocationFailure();
    verify(client).trackMetric(
        eq("$ld:ai:graph:invocation_failure"), eq(CONTEXT), eq(baseExpectedData()), eq(1.0));
  }

  @Test
  public void trackInvocationFailureIsAtMostOnce() {
    tracker.trackInvocationFailure();
    tracker.trackInvocationFailure();
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:invocation_failure"), any(), any(), anyDouble());
  }

  @Test
  public void successAndFailureShareGuard_successFirst() {
    tracker.trackInvocationSuccess();
    tracker.trackInvocationFailure();
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:invocation_success"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(
        eq("$ld:ai:graph:invocation_failure"), any(), any(), anyDouble());
    assertThat(warnings().stream().anyMatch(w -> w.contains("invocation already recorded")), is(true));
  }

  @Test
  public void successAndFailureShareGuard_failureFirst() {
    tracker.trackInvocationFailure();
    tracker.trackInvocationSuccess();
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:invocation_failure"), any(), any(), anyDouble());
    verify(client, never()).trackMetric(
        eq("$ld:ai:graph:invocation_success"), any(), any(), anyDouble());
  }

  // ---- trackDuration --------------------------------------------------------

  @Test
  public void trackDurationEmitsCorrectEvent() {
    tracker.trackDuration(250.0);
    verify(client).trackMetric(
        eq("$ld:ai:graph:duration:total"), eq(CONTEXT), eq(baseExpectedData()), eq(250.0));
  }

  @Test
  public void trackDurationIsAtMostOnce() {
    tracker.trackDuration(100.0);
    tracker.trackDuration(200.0);
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:duration:total"), any(), any(), anyDouble());
    assertThat(warnings().stream().anyMatch(w -> w.contains("duration already recorded")), is(true));
  }

  // ---- trackTotalTokens -----------------------------------------------------

  @Test
  public void trackTotalTokensEmitsCorrectEvent() {
    TokenUsage tokens = new TokenUsage(30, 20, 10);
    tracker.trackTotalTokens(tokens);
    verify(client).trackMetric(
        eq("$ld:ai:graph:total_tokens"), eq(CONTEXT), eq(baseExpectedData()), eq(30.0));
  }

  @Test
  public void trackTotalTokensIsAtMostOnce() {
    tracker.trackTotalTokens(new TokenUsage(10, 5, 5));
    tracker.trackTotalTokens(new TokenUsage(20, 10, 10));
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:total_tokens"), any(), any(), anyDouble());
    assertThat(warnings().stream().anyMatch(w -> w.contains("token usage already recorded")), is(true));
  }

  @Test
  public void trackTotalTokensAllZeroDoesNotBurnSlot() {
    tracker.trackTotalTokens(new TokenUsage(0, 0, 0));
    verify(client, never()).trackMetric(
        eq("$ld:ai:graph:total_tokens"), any(), any(), anyDouble());
    // Slot not consumed — a subsequent non-zero call should fire
    tracker.trackTotalTokens(new TokenUsage(5, 5, 0));
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:total_tokens"), any(), any(), anyDouble());
  }

  @Test
  public void trackTotalTokensNullIsIgnored() {
    tracker.trackTotalTokens(null);
    verify(client, never()).trackMetric(
        eq("$ld:ai:graph:total_tokens"), any(), any(), anyDouble());
    assertThat(debugs().stream().anyMatch(w -> w.contains("tokens was null")), is(true));
  }

  // ---- trackPath ------------------------------------------------------------

  @Test
  public void trackPathEmitsCorrectEvent() {
    List<String> path = Arrays.asList("node-a", "node-b", "node-c");
    tracker.trackPath(path);

    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(
        eq("$ld:ai:graph:path"), eq(CONTEXT), dataCaptor.capture(), eq(1.0));

    LDValue data = dataCaptor.getValue();
    assertThat(data.get("graphKey").stringValue(), is(GRAPH_KEY));
    assertThat(data.get("path").size(), is(3));
    assertThat(data.get("path").get(0).stringValue(), is("node-a"));
    assertThat(data.get("path").get(1).stringValue(), is("node-b"));
    assertThat(data.get("path").get(2).stringValue(), is("node-c"));
  }

  @Test
  public void trackPathIsAtMostOnce() {
    tracker.trackPath(Arrays.asList("node-a", "node-b"));
    tracker.trackPath(Arrays.asList("node-c"));
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:path"), any(), any(), anyDouble());
    assertThat(warnings().stream().anyMatch(w -> w.contains("path already recorded")), is(true));
  }

  @Test
  public void trackPathNullOrEmptyIsIgnored() {
    tracker.trackPath(null);
    tracker.trackPath(Arrays.asList());
    verify(client, never()).trackMetric(
        eq("$ld:ai:graph:path"), any(), any(), anyDouble());
    // Slot not consumed — a valid path should still fire
    tracker.trackPath(Arrays.asList("node-a"));
    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:path"), any(), any(), anyDouble());
  }

  // ---- trackRedirect --------------------------------------------------------

  @Test
  public void trackRedirectEmitsCorrectEvent() {
    tracker.trackRedirect("source-a", "target-b");

    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(
        eq("$ld:ai:graph:redirect"), eq(CONTEXT), dataCaptor.capture(), eq(1.0));

    LDValue data = dataCaptor.getValue();
    assertThat(data.get("sourceKey").stringValue(), is("source-a"));
    assertThat(data.get("redirectedTarget").stringValue(), is("target-b"));
    assertThat(data.get("graphKey").stringValue(), is(GRAPH_KEY));
  }

  @Test
  public void trackRedirectIsMultiFire() {
    tracker.trackRedirect("source-a", "target-b");
    tracker.trackRedirect("source-a", "target-c");
    verify(client, times(2)).trackMetric(
        eq("$ld:ai:graph:redirect"), any(), any(), anyDouble());
  }

  // ---- trackHandoffSuccess --------------------------------------------------

  @Test
  public void trackHandoffSuccessEmitsCorrectEvent() {
    tracker.trackHandoffSuccess("source-a", "target-b");

    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(
        eq("$ld:ai:graph:handoff_success"), eq(CONTEXT), dataCaptor.capture(), eq(1.0));

    LDValue data = dataCaptor.getValue();
    assertThat(data.get("sourceKey").stringValue(), is("source-a"));
    assertThat(data.get("targetKey").stringValue(), is("target-b"));
  }

  @Test
  public void trackHandoffSuccessIsMultiFire() {
    tracker.trackHandoffSuccess("source-a", "target-b");
    tracker.trackHandoffSuccess("source-b", "target-c");
    verify(client, times(2)).trackMetric(
        eq("$ld:ai:graph:handoff_success"), any(), any(), anyDouble());
  }

  // ---- trackHandoffFailure --------------------------------------------------

  @Test
  public void trackHandoffFailureEmitsCorrectEvent() {
    tracker.trackHandoffFailure("source-a", "target-b");

    ArgumentCaptor<LDValue> dataCaptor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(
        eq("$ld:ai:graph:handoff_failure"), eq(CONTEXT), dataCaptor.capture(), eq(1.0));

    LDValue data = dataCaptor.getValue();
    assertThat(data.get("sourceKey").stringValue(), is("source-a"));
    assertThat(data.get("targetKey").stringValue(), is("target-b"));
  }

  @Test
  public void trackHandoffFailureIsMultiFire() {
    tracker.trackHandoffFailure("source-a", "target-b");
    tracker.trackHandoffFailure("source-c", "target-d");
    verify(client, times(2)).trackMetric(
        eq("$ld:ai:graph:handoff_failure"), any(), any(), anyDouble());
  }

  // ---- getSummary -----------------------------------------------------------

  @Test
  public void getSummaryNullWhenNothingTracked() {
    AIGraphMetricSummary summary = tracker.getSummary();
    assertThat(summary.getSuccess(), is(nullValue()));
    assertThat(summary.getDurationMs(), is(nullValue()));
    assertThat(summary.getTokens(), is(nullValue()));
    assertThat(summary.getPath(), is(nullValue()));
    assertThat(summary.getResumptionToken(), is(notNullValue()));
  }

  @Test
  public void getSummaryReflectsTrackedValues() {
    tracker.trackInvocationSuccess();
    tracker.trackDuration(150.0);
    tracker.trackTotalTokens(new TokenUsage(40, 25, 15));
    tracker.trackPath(Arrays.asList("a", "b", "c"));

    AIGraphMetricSummary summary = tracker.getSummary();
    assertThat(summary.getSuccess(), is(Boolean.TRUE));
    assertThat(summary.getDurationMs(), is(150.0));
    assertThat(summary.getTokens().getTotal(), is(40L));
    assertThat(summary.getPath(), is(Arrays.asList("a", "b", "c")));
    assertThat(summary.getResumptionToken(), is(tracker.getResumptionToken()));
  }

  @Test
  public void getSummarySuccessIsFalseWhenFailureTracked() {
    tracker.trackInvocationFailure();
    assertThat(tracker.getSummary().getSuccess(), is(Boolean.FALSE));
  }

  // ---- variationKey in track data ------------------------------------------

  @Test
  public void variationKeyIncludedWhenPresent() {
    tracker.trackInvocationSuccess();
    ArgumentCaptor<LDValue> captor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:graph:invocation_success"), any(), captor.capture(), anyDouble());
    assertThat(captor.getValue().get("variationKey").stringValue(), is(VARIATION_KEY));
  }

  @Test
  public void variationKeyOmittedWhenNull() {
    AIGraphTracker t = makeTracker(null);
    t.trackInvocationSuccess();
    ArgumentCaptor<LDValue> captor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:graph:invocation_success"), any(), captor.capture(), anyDouble());
    assertThat(captor.getValue().get("variationKey").isNull(), is(true));
  }

  // ---- resumption token ----------------------------------------------------

  @Test
  public void getResumptionTokenIsNotNull() {
    assertThat(tracker.getResumptionToken(), is(notNullValue()));
  }

  @Test
  public void fromResumptionTokenRoundTrips() {
    String token = tracker.getResumptionToken();
    AIGraphTracker reconstructed = AIGraphTracker.fromResumptionToken(token, client, CONTEXT);
    assertThat(reconstructed.getResumptionToken(), is(token));
  }

  @Test
  public void fromResumptionTokenPreservesRunId() {
    String token = tracker.getResumptionToken();
    AIGraphTracker reconstructed = AIGraphTracker.fromResumptionToken(token, client, CONTEXT);
    // Verify same events are emitted by the reconstructed tracker
    reconstructed.trackInvocationSuccess();
    ArgumentCaptor<LDValue> captor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:graph:invocation_success"), any(), captor.capture(), anyDouble());
    assertThat(captor.getValue().get("runId").stringValue(), is(RUN_ID));
    assertThat(captor.getValue().get("graphKey").stringValue(), is(GRAPH_KEY));
  }

  @Test
  public void fromResumptionTokenWithLoggerRoutesWarningsThroughIt() {
    LogCapture captureForResumed = Logs.capture();
    LDLogger resumedLogger = LDLogger.withAdapter(captureForResumed, "test");
    String token = tracker.getResumptionToken();
    AIGraphTracker reconstructed = AIGraphTracker.fromResumptionToken(token, client, CONTEXT, resumedLogger);

    reconstructed.trackInvocationSuccess();
    reconstructed.trackInvocationSuccess(); // duplicate — should warn on resumedLogger, not the base logCapture

    List<String> resumedWarnings = captureForResumed.getMessages().stream()
        .filter(m -> m.getLevel() == LDLogLevel.WARN)
        .map(LogCapture.Message::getText)
        .collect(Collectors.toList());
    assertThat(resumedWarnings.stream().anyMatch(w -> w.contains("invocation already recorded")), is(true));
    assertThat(logCapture.getMessages(), is(org.hamcrest.Matchers.empty()));
  }

  @Test
  public void fromResumptionTokenPreservesVersionZero() {
    String token = com.launchdarkly.sdk.server.ai.internal.ResumptionTokens.encodeGraph(
        RUN_ID, GRAPH_KEY, null, 0);
    AIGraphTracker reconstructed = AIGraphTracker.fromResumptionToken(token, client, CONTEXT);
    reconstructed.trackInvocationSuccess();
    ArgumentCaptor<LDValue> captor = ArgumentCaptor.forClass(LDValue.class);
    verify(client).trackMetric(eq("$ld:ai:graph:invocation_success"), any(), captor.capture(), anyDouble());
    assertThat(captor.getValue().get("version").intValue(), is(0));
  }

  // ---- constructor null checks ---------------------------------------------

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullClient() {
    new AIGraphTracker(null, RUN_ID, GRAPH_KEY, VARIATION_KEY, VERSION, CONTEXT, logger);
  }

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullRunId() {
    new AIGraphTracker(client, null, GRAPH_KEY, VARIATION_KEY, VERSION, CONTEXT, logger);
  }

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullGraphKey() {
    new AIGraphTracker(client, RUN_ID, null, VARIATION_KEY, VERSION, CONTEXT, logger);
  }

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullContext() {
    new AIGraphTracker(client, RUN_ID, GRAPH_KEY, VARIATION_KEY, VERSION, null, logger);
  }

  // ---- concurrency: at-most-once under contention -------------------------

  @Test
  public void trackInvocationAtMostOnceUnderConcurrency() throws InterruptedException {
    int threads = 20;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService exec = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      final int idx = i;
      exec.submit(() -> {
        ready.countDown();
        try { go.await(); } catch (InterruptedException ignored) {}
        if (idx % 2 == 0) {
          tracker.trackInvocationSuccess();
        } else {
          tracker.trackInvocationFailure();
        }
      });
    }

    ready.await();
    go.countDown();
    exec.shutdown();
    exec.awaitTermination(5, TimeUnit.SECONDS);

    // Exactly one invocation event fires total
    int successCount = 0, failureCount = 0;
    try {
      verify(client, times(1)).trackMetric(
          eq("$ld:ai:graph:invocation_success"), any(), any(), anyDouble());
      successCount = 1;
    } catch (AssertionError ignored) {}
    try {
      verify(client, times(1)).trackMetric(
          eq("$ld:ai:graph:invocation_failure"), any(), any(), anyDouble());
      failureCount = 1;
    } catch (AssertionError ignored) {}
    assertThat(successCount + failureCount, is(1));
  }

  @Test
  public void trackDurationAtMostOnceUnderConcurrency() throws InterruptedException {
    int threads = 20;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService exec = Executors.newFixedThreadPool(threads);

    for (int i = 0; i < threads; i++) {
      exec.submit(() -> {
        ready.countDown();
        try { go.await(); } catch (InterruptedException ignored) {}
        tracker.trackDuration(100.0);
      });
    }

    ready.await();
    go.countDown();
    exec.shutdown();
    exec.awaitTermination(5, TimeUnit.SECONDS);

    verify(client, times(1)).trackMetric(
        eq("$ld:ai:graph:duration:total"), any(), any(), anyDouble());
  }
}
