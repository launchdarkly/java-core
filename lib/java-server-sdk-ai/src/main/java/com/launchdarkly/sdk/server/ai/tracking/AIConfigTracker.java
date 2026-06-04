package com.launchdarkly.sdk.server.ai.tracking;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.evaluation.JudgeResult;
import com.launchdarkly.sdk.server.ai.internal.ResumptionTokens;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records metrics for a single AI run.
 * <p>
 * All events emitted by a tracker share a {@code runId} (a UUIDv4) so that LaunchDarkly can correlate
 * them in metrics views. Obtain a tracker for a new run by calling {@code createTracker()} on an AI
 * Config; obtain one bound to a previous run via {@link #fromResumptionToken(String, LDClientInterface, LDContext)}.
 * <p>
 * Each scalar metric (duration, success/error, feedback, tokens, time-to-first-token) is recorded at
 * most once per tracker. Subsequent attempts are ignored and logged. Tool-call and judge-result
 * events may be recorded multiple times.
 */
public final class AIConfigTracker {
  private static final String DURATION_TOTAL = "$ld:ai:duration:total";
  private static final String TOKENS_TTF = "$ld:ai:tokens:ttf";
  private static final String FEEDBACK_POSITIVE = "$ld:ai:feedback:user:positive";
  private static final String FEEDBACK_NEGATIVE = "$ld:ai:feedback:user:negative";
  private static final String GENERATION_SUCCESS = "$ld:ai:generation:success";
  private static final String GENERATION_ERROR = "$ld:ai:generation:error";
  private static final String TOKENS_TOTAL = "$ld:ai:tokens:total";
  private static final String TOKENS_INPUT = "$ld:ai:tokens:input";
  private static final String TOKENS_OUTPUT = "$ld:ai:tokens:output";
  private static final String TOOL_CALL = "$ld:ai:tool_call";

  private final LDClientInterface client;
  private final LDLogger logger;
  private final String runId;
  private final String configKey;
  private final String variationKey;
  private final int version;
  private final LDContext context;
  private final String modelName;
  private final String providerName;
  private final String graphKey;
  private final MetricSummary summary = new MetricSummary();

  private AIConfigTracker(Builder builder) {
    this.client = builder.client;
    this.logger = builder.logger != null ? builder.logger
        : (builder.client.getLogger() != null ? builder.client.getLogger() : LDLogger.none());
    this.runId = builder.runId;
    this.configKey = builder.configKey;
    this.variationKey = builder.variationKey;
    this.version = builder.version;
    this.context = builder.context;
    this.modelName = builder.modelName == null ? "" : builder.modelName;
    this.providerName = builder.providerName == null ? "" : builder.providerName;
    this.graphKey = builder.graphKey;
    // Capture the resumption token immediately so it is available on the summary at construction.
    this.summary.setResumptionToken(getResumptionToken());
  }

  /**
   * Returns a URL-safe Base64-encoded token that can be used to reconstruct this tracker in a
   * different process (for example to record deferred feedback).
   * <p>
   * The token contains the {@code runId}, {@code configKey}, {@code version}, and optionally the
   * {@code variationKey} and {@code graphKey}. It does <em>not</em> contain the model or provider
   * name.
   * <p>
   * <strong>Security note:</strong> the token contains the flag variation key and version. If passed
   * to an untrusted client (such as a browser) this could expose feature flag targeting details.
   * Consider keeping the token server-side and exposing only an opaque reference to it.
   *
   * @return the resumption token
   */
  public String getResumptionToken() {
    return ResumptionTokens.encode(runId, configKey, variationKey, version, graphKey);
  }

  /**
   * Reconstructs a tracker from a resumption token, binding it to the original run's identity.
   *
   * @param token a resumption token previously produced by {@link #getResumptionToken()}
   * @param client the LaunchDarkly client used for subsequent track calls
   * @param context the context to use for subsequent track calls
   * @return a tracker bound to the original {@code runId}
   * @throws IllegalArgumentException if the token is invalid or missing a required field
   */
  public static AIConfigTracker fromResumptionToken(String token, LDClientInterface client, LDContext context) {
    ResumptionTokens.Data data = ResumptionTokens.decode(token);
    return builder(client)
        .runId(data.getRunId())
        .configKey(data.getConfigKey())
        .variationKey(data.getVariationKey())
        .version(data.getVersion())
        .context(context)
        .graphKey(data.getGraphKey())
        .build();
  }

  /**
   * Returns a summary of all metrics tracked so far, including the tracker's resumption token.
   *
   * @return the metric summary
   */
  public MetricSummary getSummary() {
    return summary;
  }

  /**
   * Tracks the duration of an AI run. Recorded at most once per tracker.
   *
   * @param durationMs the duration in milliseconds
   */
  public void trackDuration(long durationMs) {
    if (summary.getDurationMs() != null) {
      warnAlreadyRecorded("trackDuration", "duration");
      return;
    }
    summary.setDurationMs(durationMs);
    client.trackMetric(DURATION_TOTAL, context, getTrackData(), durationMs);
  }

  /**
   * Tracks the duration of the supplied operation, then returns its result. The duration is recorded
   * even if the operation throws, and the exception is rethrown.
   *
   * @param operation the operation to time
   * @param <T> the operation's result type
   * @return the operation's result
   */
  public <T> T trackDurationOf(Supplier<T> operation) {
    long startNanos = System.nanoTime();
    try {
      return operation.get();
    } finally {
      trackDuration(elapsedMillis(startNanos));
    }
  }

  /**
   * Tracks the time to first token for a completion. Recorded at most once per tracker.
   *
   * @param timeToFirstTokenMs the time to first token in milliseconds
   */
  public void trackTimeToFirstToken(long timeToFirstTokenMs) {
    if (summary.getTimeToFirstTokenMs() != null) {
      warnAlreadyRecorded("trackTimeToFirstToken", "time-to-first-token");
      return;
    }
    summary.setTimeToFirstTokenMs(timeToFirstTokenMs);
    client.trackMetric(TOKENS_TTF, context, getTrackData(), timeToFirstTokenMs);
  }

  /**
   * Tracks user feedback for an AI run. Recorded at most once per tracker.
   *
   * @param feedback the feedback kind
   */
  public void trackFeedback(FeedbackKind feedback) {
    if (summary.getFeedback() != null) {
      warnAlreadyRecorded("trackFeedback", "feedback");
      return;
    }
    summary.setFeedback(feedback);
    String eventName = feedback == FeedbackKind.POSITIVE ? FEEDBACK_POSITIVE : FEEDBACK_NEGATIVE;
    client.trackMetric(eventName, context, getTrackData(), 1);
  }

  /**
   * Tracks a successful AI generation. Recorded at most once per tracker; shares state with
   * {@link #trackError()}.
   */
  public void trackSuccess() {
    if (summary.getSuccess() != null) {
      warnAlreadyRecorded("trackSuccess", "success/error");
      return;
    }
    summary.setSuccess(true);
    client.trackMetric(GENERATION_SUCCESS, context, getTrackData(), 1);
  }

  /**
   * Tracks an unsuccessful AI generation. Recorded at most once per tracker; shares state with
   * {@link #trackSuccess()}.
   */
  public void trackError() {
    if (summary.getSuccess() != null) {
      warnAlreadyRecorded("trackError", "success/error");
      return;
    }
    summary.setSuccess(false);
    client.trackMetric(GENERATION_ERROR, context, getTrackData(), 1);
  }

  /**
   * Tracks token usage. Recorded at most once per tracker. Only the positive token counts produce
   * events.
   *
   * @param tokens the token usage
   */
  public void trackTokens(TokenUsage tokens) {
    if (summary.getTokens() != null) {
      warnAlreadyRecorded("trackTokens", "token usage");
      return;
    }
    summary.setTokens(tokens);
    LDValue trackData = getTrackData();
    if (tokens.getTotal() > 0) {
      client.trackMetric(TOKENS_TOTAL, context, trackData, tokens.getTotal());
    }
    if (tokens.getInput() > 0) {
      client.trackMetric(TOKENS_INPUT, context, trackData, tokens.getInput());
    }
    if (tokens.getOutput() > 0) {
      client.trackMetric(TOKENS_OUTPUT, context, trackData, tokens.getOutput());
    }
  }

  /**
   * Tracks a single tool invocation. May be called multiple times per tracker.
   *
   * @param toolKey the identifier of the tool that was invoked
   */
  public void trackToolCall(String toolKey) {
    summary.addToolCall(toolKey);
    LDValue trackData = trackDataBuilder().put("toolKey", toolKey).build();
    client.trackMetric(TOOL_CALL, context, trackData, 1);
  }

  /**
   * Tracks multiple tool invocations by delegating to {@link #trackToolCall(String)} for each key.
   *
   * @param toolKeys the identifiers of the tools that were invoked
   */
  public void trackToolCalls(Iterable<String> toolKeys) {
    for (String toolKey : toolKeys) {
      trackToolCall(toolKey);
    }
  }

  /**
   * Tracks a single judge evaluation result using the result's metric key and score.
   * <p>
   * No event is emitted when the result was not sampled or did not succeed.
   *
   * @param judgeResult the judge result to track
   */
  public void trackJudgeResult(JudgeResult judgeResult) {
    if (!judgeResult.isSampled()) {
      return;
    }
    if (judgeResult.isSuccess() && judgeResult.getMetricKey() != null) {
      ObjectBuilder builder = trackDataBuilder();
      if (judgeResult.getJudgeConfigKey() != null) {
        builder.put("judgeConfigKey", judgeResult.getJudgeConfigKey());
      }
      double score = judgeResult.getScore() == null ? 0.0 : judgeResult.getScore();
      client.trackMetric(judgeResult.getMetricKey(), context, builder.build(), score);
    }
  }

  /**
   * Runs the supplied operation, extracts metrics from its result, and records duration,
   * success/error, token usage, and tool calls automatically.
   * <p>
   * If the operation throws, the duration and an error are recorded and the exception is rethrown.
   * If the extracted metrics provide a {@code durationMs}, that value is used instead of the measured
   * wall-clock time.
   *
   * @param metricsExtractor a function that extracts {@link AIMetrics} from the operation result
   *     (may return {@code null} to record only the duration)
   * @param operation the operation to run and track
   * @param <T> the operation's result type
   * @return the operation's result
   */
  public <T> T trackMetricsOf(Function<T, AIMetrics> metricsExtractor, Supplier<T> operation) {
    long startNanos = System.nanoTime();
    T result;
    try {
      result = operation.get();
    } catch (RuntimeException e) {
      trackDuration(elapsedMillis(startNanos));
      trackError();
      throw e;
    }

    long elapsedMs = elapsedMillis(startNanos);
    AIMetrics metrics = null;
    try {
      metrics = metricsExtractor.apply(result);
    } catch (RuntimeException e) {
      logger.warn("Failed to extract metrics: {}", e.toString());
    }

    if (metrics == null) {
      trackDuration(elapsedMs);
      return result;
    }

    trackDuration(metrics.getDurationMs() != null ? metrics.getDurationMs() : elapsedMs);
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

  private LDValue getTrackData() {
    return trackDataBuilder().build();
  }

  private ObjectBuilder trackDataBuilder() {
    ObjectBuilder builder = LDValue.buildObject()
        .put("runId", runId)
        .put("configKey", configKey)
        .put("version", version)
        .put("modelName", modelName)
        .put("providerName", providerName);
    if (variationKey != null && !variationKey.isEmpty()) {
      builder.put("variationKey", variationKey);
    }
    if (graphKey != null && !graphKey.isEmpty()) {
      builder.put("graphKey", graphKey);
    }
    return builder;
  }

  private void warnAlreadyRecorded(String method, String metric) {
    logger.warn(
        "Skipping {}: {} already recorded on this tracker. Call createTracker on the AI Config for a new run. {}",
        method, metric, getTrackData());
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Creates a builder for an {@link AIConfigTracker}.
   *
   * @param client the LaunchDarkly client used to emit events
   * @return a new builder
   */
  public static Builder builder(LDClientInterface client) {
    return new Builder(client);
  }

  /**
   * A builder for {@link AIConfigTracker} instances. Used by the SDK to construct trackers; not
   * normally needed by application code.
   */
  public static final class Builder {
    private final LDClientInterface client;
    private LDLogger logger;
    private String runId;
    private String configKey;
    private String variationKey = "";
    private int version = 1;
    private LDContext context;
    private String modelName = "";
    private String providerName = "";
    private String graphKey;

    private Builder(LDClientInterface client) {
      this.client = client;
    }

    /** @param logger the logger to use @return this builder */
    public Builder logger(LDLogger logger) {
      this.logger = logger;
      return this;
    }

    /** @param runId the run id (UUIDv4) for this tracker @return this builder */
    public Builder runId(String runId) {
      this.runId = runId;
      return this;
    }

    /** @param configKey the configuration key @return this builder */
    public Builder configKey(String configKey) {
      this.configKey = configKey;
      return this;
    }

    /** @param variationKey the variation key, or empty if none @return this builder */
    public Builder variationKey(String variationKey) {
      this.variationKey = variationKey == null ? "" : variationKey;
      return this;
    }

    /** @param version the variation version @return this builder */
    public Builder version(int version) {
      this.version = version;
      return this;
    }

    /** @param context the evaluation context @return this builder */
    public Builder context(LDContext context) {
      this.context = context;
      return this;
    }

    /** @param modelName the model name @return this builder */
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /** @param providerName the provider name @return this builder */
    public Builder providerName(String providerName) {
      this.providerName = providerName;
      return this;
    }

    /** @param graphKey the containing graph key, or {@code null} @return this builder */
    public Builder graphKey(String graphKey) {
      this.graphKey = graphKey;
      return this;
    }

    /**
     * Builds the tracker.
     *
     * @return a new {@link AIConfigTracker}
     */
    public AIConfigTracker build() {
      return new AIConfigTracker(this);
    }
  }
}
