package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The default {@link LDAIConfigTracker} implementation.
 * <p>
 * Tracks AI run metrics and emits them as LaunchDarkly custom events via the wrapped
 * {@link LDClientInterface}. At-most-once semantics for each metric type are enforced using
 * {@link AtomicReference#compareAndSet} — a single atomic operation that serves as both guard
 * and value store, eliminating the race window present in a two-step check-then-act pattern.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class LDAIConfigTrackerImpl implements LDAIConfigTracker {

  private static final String DURATION_TOTAL = "$ld:ai:duration:total";
  private static final String TOKENS_TTF = "$ld:ai:tokens:ttf";
  private static final String GENERATION_SUCCESS = "$ld:ai:generation:success";
  private static final String GENERATION_ERROR = "$ld:ai:generation:error";
  private static final String FEEDBACK_POSITIVE = "$ld:ai:feedback:user:positive";
  private static final String FEEDBACK_NEGATIVE = "$ld:ai:feedback:user:negative";
  private static final String TOKENS_TOTAL = "$ld:ai:tokens:total";
  private static final String TOKENS_INPUT = "$ld:ai:tokens:input";
  private static final String TOKENS_OUTPUT = "$ld:ai:tokens:output";
  private static final String TOOL_CALL = "$ld:ai:tool_call";

  private final LDClientInterface client;
  private final LDContext context;
  private final LDLogger logger;

  // Identity fields
  private final String runId;
  private final String configKey;
  private final String variationKey; // nullable — null when using a default config
  private final int version;
  private final String modelName;   // empty string when unknown
  private final String providerName; // empty string when unknown
  private final String graphKey;    // nullable

  // Computed once at construction
  private final String resumptionToken;

  // At-most-once slots: null = not yet recorded, non-null = recorded with this value.
  // AtomicReference.compareAndSet(null, value) is a single atomic operation — both guard and
  // value store — eliminating the race window in an AtomicBoolean + volatile approach.
  private final AtomicReference<Long> durationMs = new AtomicReference<>();
  private final AtomicReference<Long> timeToFirstTokenMs = new AtomicReference<>();
  // Shared by trackSuccess and trackError: true = success, false = error
  private final AtomicReference<Boolean> outcome = new AtomicReference<>();
  private final AtomicReference<FeedbackKind> feedbackRef = new AtomicReference<>();
  private final AtomicReference<TokenUsage> tokensRef = new AtomicReference<>();

  // Multi-fire accumulator — not at-most-once
  private final CopyOnWriteArrayList<String> toolCalls = new CopyOnWriteArrayList<>();

  /**
   * Creates a tracker for a new AI run.
   *
   * @param client the LaunchDarkly client used to emit events; must not be {@code null}
   * @param runId the unique run identifier (UUID v4); must not be {@code null}
   * @param configKey the AI Config key; must not be {@code null}
   * @param variationKey the variation key, or {@code null} when using a default config
   * @param version the config version
   * @param modelName the model name, or empty string when unknown
   * @param providerName the provider name, or empty string when unknown
   * @param context the evaluation context; must not be {@code null}
   * @param graphKey the agent graph key, or {@code null} when not part of a graph
   * @param logger the logger; must not be {@code null}
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
    this.client = Objects.requireNonNull(client, "client");
    this.runId = Objects.requireNonNull(runId, "runId");
    this.configKey = Objects.requireNonNull(configKey, "configKey");
    this.variationKey = variationKey;
    this.version = version;
    this.modelName = modelName == null ? "" : modelName;
    this.providerName = providerName == null ? "" : providerName;
    this.context = Objects.requireNonNull(context, "context");
    this.graphKey = graphKey;
    this.logger = Objects.requireNonNull(logger, "logger");

    // Compute once at construction — all inputs are immutable.
    this.resumptionToken = ResumptionTokens.encode(runId, configKey, variationKey, version, graphKey);
  }

  /**
   * Reconstructs a tracker from a resumption token, preserving the original run's identity.
   *
   * @param token the resumption token
   * @param client the LaunchDarkly client; must not be {@code null}
   * @param context the evaluation context; must not be {@code null}
   * @param logger the logger; must not be {@code null}
   * @return a new tracker with the decoded run identity
   * @throws IllegalArgumentException if the token is malformed
   */
  public static LDAIConfigTrackerImpl fromResumptionToken(
      String token, LDClientInterface client, LDContext context, LDLogger logger) {
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    return new LDAIConfigTrackerImpl(
        client,
        d.getRunId(),
        d.getConfigKey(),
        d.getVariationKey(),
        d.getVersion(),
        "", // modelName not carried in token
        "", // providerName not carried in token
        context,
        d.getGraphKey(),
        logger);
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
      logger.debug("Skipping trackDuration: duration was null.");
      return;
    }
    long ms = Math.max(0L, duration.toMillis());
    if (!durationMs.compareAndSet(null, ms)) {
      logger.warn("Skipping trackDuration: duration already recorded on this tracker.");
      return;
    }
    client.trackMetric(DURATION_TOTAL, context, baseData().build(), ms);
  }

  @Override
  public <T> T trackDurationOf(Callable<T> operation) throws Exception {
    Objects.requireNonNull(operation, "operation");
    long start = System.nanoTime();
    try {
      return operation.call();
    } finally {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      trackDuration(Duration.ofMillis(elapsedMs));
    }
  }

  @Override
  public void trackTimeToFirstToken(Duration duration) {
    if (duration == null) {
      logger.debug("Skipping trackTimeToFirstToken: duration was null.");
      return;
    }
    long ms = Math.max(0L, duration.toMillis());
    if (!timeToFirstTokenMs.compareAndSet(null, ms)) {
      logger.warn("Skipping trackTimeToFirstToken: time-to-first-token already recorded on this tracker.");
      return;
    }
    client.trackMetric(TOKENS_TTF, context, baseData().build(), ms);
  }

  @Override
  public void trackSuccess() {
    if (!outcome.compareAndSet(null, Boolean.TRUE)) {
      logger.warn("Skipping trackSuccess: outcome already recorded on this tracker.");
      return;
    }
    client.trackMetric(GENERATION_SUCCESS, context, baseData().build(), 1);
  }

  @Override
  public void trackError() {
    if (!outcome.compareAndSet(null, Boolean.FALSE)) {
      logger.warn("Skipping trackError: outcome already recorded on this tracker.");
      return;
    }
    client.trackMetric(GENERATION_ERROR, context, baseData().build(), 1);
  }

  @Override
  public void trackFeedback(FeedbackKind kind) {
    if (kind == null) {
      logger.debug("Skipping trackFeedback: kind was null.");
      return;
    }
    // Resolve event name BEFORE claiming the guard — an exception here must not burn the slot.
    String eventName = kind == FeedbackKind.POSITIVE ? FEEDBACK_POSITIVE : FEEDBACK_NEGATIVE;
    if (!feedbackRef.compareAndSet(null, kind)) {
      logger.warn("Skipping trackFeedback: feedback already recorded on this tracker.");
      return;
    }
    client.trackMetric(eventName, context, baseData().build(), 1);
  }

  @Override
  public void trackTokens(TokenUsage tokens) {
    if (tokens == null) {
      logger.debug("Skipping trackTokens: tokens was null.");
      return;
    }
    boolean hasPositive = tokens.getTotal() > 0 || tokens.getInput() > 0 || tokens.getOutput() > 0;
    if (!hasPositive) {
      // Do not burn the at-most-once slot when all counts are zero.
      return;
    }
    if (!tokensRef.compareAndSet(null, tokens)) {
      logger.warn("Skipping trackTokens: token usage already recorded on this tracker.");
      return;
    }
    if (tokens.getTotal() > 0) {
      client.trackMetric(TOKENS_TOTAL, context, baseData().build(), tokens.getTotal());
    }
    if (tokens.getInput() > 0) {
      client.trackMetric(TOKENS_INPUT, context, baseData().build(), tokens.getInput());
    }
    if (tokens.getOutput() > 0) {
      client.trackMetric(TOKENS_OUTPUT, context, baseData().build(), tokens.getOutput());
    }
  }

  @Override
  public void trackToolCall(String toolKey) {
    if (toolKey == null) {
      logger.debug("Skipping trackToolCall: toolKey was null.");
      return;
    }
    toolCalls.add(toolKey);
    LDValue data = baseData().put("toolKey", toolKey).build();
    client.trackMetric(TOOL_CALL, context, data, 1);
  }

  @Override
  public void trackToolCalls(List<String> toolKeys) {
    if (toolKeys == null) {
      return;
    }
    for (String key : toolKeys) {
      trackToolCall(key);
    }
  }

  @Override
  public void trackJudgeResult(JudgeResult result) {
    if (result == null) {
      logger.debug("Skipping trackJudgeResult: result was null.");
      return;
    }
    if (!result.isSampled()) {
      return;
    }
    if (!result.isSuccess()) {
      return;
    }
    if (result.getMetricKey() == null || result.getMetricKey().trim().isEmpty()) {
      return;
    }
    if (result.getScore() == null || !Double.isFinite(result.getScore())) {
      return;
    }
    ObjectBuilder data = baseData();
    if (result.getJudgeConfigKey() != null) {
      data.put("judgeConfigKey", result.getJudgeConfigKey());
    }
    client.trackMetric(result.getMetricKey(), context, data.build(), result.getScore());
  }

  @Override
  public <T> T trackMetricsOf(
      Function<? super T, AIMetrics> metricsExtractor,
      Callable<T> operation) throws Exception {
    Objects.requireNonNull(metricsExtractor, "metricsExtractor");
    Objects.requireNonNull(operation, "operation");

    long start = System.nanoTime();
    T result;
    try {
      result = operation.call();
    } catch (Exception e) {
      // Operation failed — track measured duration + error, then re-throw.
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      trackDuration(Duration.ofMillis(elapsed));
      trackError();
      throw e;
    }
    // Capture operation duration immediately so a slow extractor does not inflate the metric.
    long operationElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    // Extractor exceptions propagate to the caller, but the operation's duration must still be
    // recorded — the AI operation itself succeeded, only the user-supplied extractor failed.
    // Do NOT call trackError(); that signals the operation failed, which is not what happened.
    AIMetrics metrics;
    try {
      metrics = Objects.requireNonNull(metricsExtractor.apply(result), "metricsExtractor returned null");
    } catch (RuntimeException e) {
      trackDuration(Duration.ofMillis(operationElapsedMs));
      throw e;
    }

    // Duration: prefer runner-reported value (§1.1.13.2), fall back to wall-clock.
    if (metrics.getDurationMs() != null) {
      trackDuration(Duration.ofMillis(metrics.getDurationMs()));
    } else {
      trackDuration(Duration.ofMillis(operationElapsedMs));
    }

    if (metrics.isSuccess()) {
      trackSuccess();
    } else {
      trackError();
    }

    if (metrics.getTokens() != null) {
      trackTokens(metrics.getTokens());
    }
    if (metrics.getToolCalls() != null) {
      trackToolCalls(metrics.getToolCalls());
    }

    return result;
  }

  @Override
  public MetricSummary getSummary() {
    List<String> snapshot = toolCalls.isEmpty()
        ? null
        : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    return new MetricSummary(
        outcome.get(),
        tokensRef.get(),
        durationMs.get(),
        feedbackRef.get(),
        timeToFirstTokenMs.get(),
        snapshot,
        resumptionToken);
  }

  /**
   * Returns a pre-populated {@link LDValue.ObjectBuilder} containing the base track-data fields.
   * Individual track methods add per-event fields before calling {@link LDValue.ObjectBuilder#build()}.
   */
  private ObjectBuilder baseData() {
    ObjectBuilder b = LDValue.buildObject()
        .put("runId", runId)
        .put("configKey", configKey)
        .put("version", version)
        .put("modelName", modelName)
        .put("providerName", providerName);
    if (variationKey != null) {
      b.put("variationKey", variationKey);
    }
    if (graphKey != null) {
      b.put("graphKey", graphKey);
    }
    return b;
  }
}
