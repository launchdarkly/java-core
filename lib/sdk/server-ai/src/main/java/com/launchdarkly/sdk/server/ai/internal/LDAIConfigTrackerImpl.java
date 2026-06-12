package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The production {@link LDAIConfigTracker}. Emits AI metric events through the base
 * {@code LDClient} using a shared {@code runId} and the config's correlation data.
 * <p>
 * <strong>Thread-safety.</strong> The class is safe for concurrent use. Each record-once metric is
 * guarded by an {@link AtomicBoolean}: a writer claims the guard with {@code compareAndSet} before
 * it stores the value and emits, so exactly one event is produced no matter how many threads call
 * concurrently. {@code trackSuccess} and {@code trackError} share a single guard. Tool calls are
 * accumulated in a {@link CopyOnWriteArrayList}; tool-call and judge-result events are not
 * record-once and emit on every call. Summary fields are written only by the thread that wins the
 * guard and are declared {@code volatile} so {@link #getSummary()} observes a consistent snapshot.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class LDAIConfigTrackerImpl implements LDAIConfigTracker {
  private static final String DURATION_TOTAL = "$ld:ai:duration:total";
  private static final String TIME_TO_FIRST_TOKEN = "$ld:ai:tokens:ttf";
  private static final String TOKENS_TOTAL = "$ld:ai:tokens:total";
  private static final String TOKENS_INPUT = "$ld:ai:tokens:input";
  private static final String TOKENS_OUTPUT = "$ld:ai:tokens:output";
  private static final String GENERATION_SUCCESS = "$ld:ai:generation:success";
  private static final String GENERATION_ERROR = "$ld:ai:generation:error";
  private static final String FEEDBACK_POSITIVE = "$ld:ai:feedback:user:positive";
  private static final String FEEDBACK_NEGATIVE = "$ld:ai:feedback:user:negative";
  private static final String TOOL_CALL = "$ld:ai:tool_call";

  private final LDClientInterface client;
  private final LDLogger logger;
  private final LDContext context;
  private final String runId;
  private final String configKey;
  private final String variationKey;
  private final int version;
  private final String modelName;
  private final String providerName;
  private final String graphKey;
  private final String resumptionToken;

  private final AtomicBoolean durationRecorded = new AtomicBoolean(false);
  private final AtomicBoolean timeToFirstTokenRecorded = new AtomicBoolean(false);
  private final AtomicBoolean outcomeRecorded = new AtomicBoolean(false);
  private final AtomicBoolean feedbackRecorded = new AtomicBoolean(false);
  private final AtomicBoolean tokensRecorded = new AtomicBoolean(false);
  private final CopyOnWriteArrayList<String> toolCalls = new CopyOnWriteArrayList<>();

  private volatile Long durationMs;
  private volatile Long timeToFirstTokenMs;
  private volatile Boolean success;
  private volatile FeedbackKind feedback;
  private volatile TokenUsage tokens;

  /**
   * Creates a tracker for a single AI run.
   *
   * @param client the base client used to emit events; must not be {@code null}
   * @param runId the per-run UUID shared by all of the run's events
   * @param configKey the AI Config key
   * @param variationKey the variation key, or empty string when unknown
   * @param version the AI Config version
   * @param modelName the model name, or empty string when unknown
   * @param providerName the provider name, or empty string when unknown
   * @param context the evaluation context the events are attributed to
   * @param graphKey the graph key, or {@code null} when not part of a graph
   * @param logger the logger used for skip warnings; must not be {@code null}
   */
  public LDAIConfigTrackerImpl(
      LDClientInterface client,
      String runId,
      String configKey,
      String variationKey,
      int version,
      String modelName,
      String providerName,
      LDContext context,
      String graphKey,
      LDLogger logger) {
    this.client = client;
    this.runId = runId;
    this.configKey = configKey;
    this.variationKey = variationKey == null ? "" : variationKey;
    this.version = version;
    this.modelName = modelName == null ? "" : modelName;
    this.providerName = providerName == null ? "" : providerName;
    this.context = context;
    this.graphKey = graphKey;
    this.logger = logger;
    this.resumptionToken =
        ResumptionTokens.encode(this.runId, this.configKey, this.variationKey, this.version, this.graphKey);
  }

  /**
   * Reconstructs a tracker from a resumption token so deferred events correlate with the original
   * run. The restored tracker shares the original {@code runId} but reports empty model and provider
   * names, which are not carried in the token.
   *
   * @param token the resumption token
   * @param client the base client used to emit events
   * @param context the evaluation context the events are attributed to
   * @param logger the logger used for skip warnings
   * @return a tracker sharing the original run's identity
   * @throws IllegalArgumentException if the token is malformed
   */
  public static LDAIConfigTrackerImpl fromResumptionToken(
      String token, LDClientInterface client, LDContext context, LDLogger logger) {
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    return new LDAIConfigTrackerImpl(
        client, d.getRunId(), d.getConfigKey(), d.getVariationKey(), d.getVersion(),
        "", "", context, d.getGraphKey(), logger);
  }

  @Override
  public TrackData getTrackData() {
    return new TrackData(runId, configKey, variationKey, version, modelName, providerName, graphKey);
  }

  @Override
  public String getResumptionToken() {
    return resumptionToken;
  }

  @Override
  public void trackDuration(Duration duration) {
    if (duration == null) {
      logger.warn("Skipping trackDuration: duration was null.");
      return;
    }
    if (!durationRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackDuration: duration already recorded on this tracker.");
      return;
    }
    long ms = Math.max(0L, duration.toMillis());
    this.durationMs = ms;
    client.trackMetric(DURATION_TOTAL, context, baseData().build(), ms);
  }

  @Override
  public <T> T trackDurationOf(Callable<T> operation) throws Exception {
    if (operation == null) {
      throw new NullPointerException("operation must not be null");
    }
    long start = System.nanoTime();
    try {
      return operation.call();
    } finally {
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      trackDuration(Duration.ofMillis(elapsedMs));
    }
  }

  @Override
  public void trackTimeToFirstToken(Duration duration) {
    if (duration == null) {
      logger.warn("Skipping trackTimeToFirstToken: duration was null.");
      return;
    }
    if (!timeToFirstTokenRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackTimeToFirstToken: time to first token already recorded on this tracker.");
      return;
    }
    long ms = Math.max(0L, duration.toMillis());
    this.timeToFirstTokenMs = ms;
    client.trackMetric(TIME_TO_FIRST_TOKEN, context, baseData().build(), ms);
  }

  @Override
  public void trackSuccess() {
    if (!outcomeRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackSuccess: success or error already recorded on this tracker.");
      return;
    }
    this.success = Boolean.TRUE;
    client.trackMetric(GENERATION_SUCCESS, context, baseData().build(), 1);
  }

  @Override
  public void trackError() {
    if (!outcomeRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackError: success or error already recorded on this tracker.");
      return;
    }
    this.success = Boolean.FALSE;
    client.trackMetric(GENERATION_ERROR, context, baseData().build(), 1);
  }

  @Override
  public void trackFeedback(FeedbackKind kind) {
    if (kind == null) {
      logger.warn("Skipping trackFeedback: feedback kind was null.");
      return;
    }
    if (!feedbackRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackFeedback: feedback already recorded on this tracker.");
      return;
    }
    this.feedback = kind;
    String event = kind == FeedbackKind.POSITIVE ? FEEDBACK_POSITIVE : FEEDBACK_NEGATIVE;
    client.trackMetric(event, context, baseData().build(), 1);
  }

  @Override
  public void trackTokens(TokenUsage rawTokens) {
    if (rawTokens == null) {
      logger.warn("Skipping trackTokens: token usage was null.");
      return;
    }
    if (!tokensRecorded.compareAndSet(false, true)) {
      logger.warn("Skipping trackTokens: token usage already recorded on this tracker.");
      return;
    }
    long total = Math.max(0L, rawTokens.getTotal());
    long input = Math.max(0L, rawTokens.getInput());
    long output = Math.max(0L, rawTokens.getOutput());
    this.tokens = new TokenUsage(total, input, output);
    if (total > 0L) {
      client.trackMetric(TOKENS_TOTAL, context, baseData().build(), total);
    }
    if (input > 0L) {
      client.trackMetric(TOKENS_INPUT, context, baseData().build(), input);
    }
    if (output > 0L) {
      client.trackMetric(TOKENS_OUTPUT, context, baseData().build(), output);
    }
  }

  @Override
  public void trackToolCall(String toolKey) {
    if (toolKey == null) {
      logger.warn("Skipping trackToolCall: tool key was null.");
      return;
    }
    toolCalls.add(toolKey);
    client.trackMetric(TOOL_CALL, context, baseData().put("toolKey", toolKey).build(), 1);
  }

  @Override
  public void trackToolCalls(List<String> toolKeys) {
    if (toolKeys == null) {
      logger.warn("Skipping trackToolCalls: tool keys were null.");
      return;
    }
    for (String toolKey : toolKeys) {
      trackToolCall(toolKey);
    }
  }

  @Override
  public void trackJudgeResult(JudgeResult result) {
    if (result == null) {
      logger.warn("Skipping trackJudgeResult: result was null.");
      return;
    }
    if (!result.isSampled() || !result.isSuccess()) {
      return;
    }
    if (result.getMetricKey() == null || result.getScore() == null) {
      return;
    }
    ObjectBuilder data = baseData();
    if (result.getJudgeConfigKey() != null) {
      data.put("judgeConfigKey", result.getJudgeConfigKey());
    }
    client.trackMetric(result.getMetricKey(), context, data.build(), result.getScore());
  }

  @Override
  public <T> T trackMetricsOf(Function<? super T, AIMetrics> metricsExtractor, Callable<T> operation)
      throws Exception {
    if (metricsExtractor == null) {
      throw new NullPointerException("metricsExtractor must not be null");
    }
    if (operation == null) {
      throw new NullPointerException("operation must not be null");
    }

    T result;
    try {
      result = trackDurationOf(operation);
    } catch (Exception e) {
      trackError();
      throw e;
    }

    // The extractor runs only after the operation has already succeeded, so a failure here is a
    // metric-extraction problem, not an AI generation failure. Let it propagate without recording a
    // generation error (matching the .NET SDK).
    AIMetrics metrics = metricsExtractor.apply(result);

    if (metrics != null) {
      if (metrics.isSuccess()) {
        trackSuccess();
      } else {
        trackError();
      }
      if (metrics.getTokens() != null) {
        trackTokens(metrics.getTokens());
      }
      List<String> calls = metrics.getToolCalls();
      if (calls != null && !calls.isEmpty()) {
        trackToolCalls(calls);
      }
    }
    return result;
  }

  @Override
  public MetricSummary getSummary() {
    MetricSummary.Builder b = MetricSummary.builder()
        .success(success)
        .tokens(tokens)
        .durationMs(durationMs)
        .timeToFirstTokenMs(timeToFirstTokenMs)
        .feedback(feedback)
        .resumptionToken(resumptionToken);
    if (!toolCalls.isEmpty()) {
      b.toolCalls(new ArrayList<>(toolCalls));
    }
    return b.build();
  }

  private ObjectBuilder baseData() {
    ObjectBuilder b = LDValue.buildObject()
        .put("runId", runId)
        .put("configKey", configKey)
        .put("variationKey", variationKey)
        .put("version", version)
        .put("modelName", modelName)
        .put("providerName", providerName);
    if (graphKey != null) {
      b.put("graphKey", graphKey);
    }
    return b;
  }
}
