package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Container for the value types used when reporting metrics about an AI run through an
 * {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker}.
 * <p>
 * These are simple, immutable data carriers (token usage, feedback sentiment, the metrics a caller
 * extracts from a model response, a judge result, the per-run summary, and the data attached to
 * every tracking event). They are grouped under one type to keep the public surface compact and to
 * avoid claiming generic top-level names such as {@code TokenUsage} or {@code Metrics}.
 * <p>
 * This class cannot be instantiated.
 */
public final class LDAITrackingTypes {
  private LDAITrackingTypes() {
  }

  /**
   * Sentiment of end-user feedback about a generation.
   */
  public enum FeedbackKind {
    /**
     * Positive sentiment.
     */
    POSITIVE("positive"),
    /**
     * Negative sentiment.
     */
    NEGATIVE("negative");

    private final String value;

    FeedbackKind(String value) {
      this.value = value;
    }

    /**
     * Returns the wire value used in event names for this sentiment.
     *
     * @return the wire value, either {@code "positive"} or {@code "negative"}
     */
    public String getValue() {
      return value;
    }
  }

  /**
   * Token usage reported for a single generation.
   * <p>
   * Counts are non-negative; negative inputs are clamped to zero when recorded.
   */
  public static final class TokenUsage {
    private final long total;
    private final long input;
    private final long output;

    /**
     * Creates a token-usage record.
     *
     * @param total the combined token count
     * @param input the number of input (prompt) tokens
     * @param output the number of output (completion) tokens
     */
    public TokenUsage(long total, long input, long output) {
      this.total = total;
      this.input = input;
      this.output = output;
    }

    /**
     * Returns the combined token count.
     *
     * @return the total token count
     */
    public long getTotal() {
      return total;
    }

    /**
     * Returns the number of input (prompt) tokens.
     *
     * @return the input token count
     */
    public long getInput() {
      return input;
    }

    /**
     * Returns the number of output (completion) tokens.
     *
     * @return the output token count
     */
    public long getOutput() {
      return output;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TokenUsage)) {
        return false;
      }
      TokenUsage that = (TokenUsage) o;
      return total == that.total && input == that.input && output == that.output;
    }

    @Override
    public int hashCode() {
      return Objects.hash(total, input, output);
    }

    @Override
    public String toString() {
      return "TokenUsage{total=" + total + ", input=" + input + ", output=" + output + '}';
    }
  }

  /**
   * Metrics a caller extracts from an AI run, supplied to
   * {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker#trackMetricsOf}.
   */
  public static final class Metrics {
    private final boolean success;
    private final TokenUsage tokens;
    private final List<String> toolCalls;
    private final Long durationMs;

    private Metrics(Builder b) {
      this.success = b.success;
      this.tokens = b.tokens;
      this.toolCalls = b.toolCalls == null
          ? null : Collections.unmodifiableList(new ArrayList<>(b.toolCalls));
      this.durationMs = b.durationMs;
    }

    /**
     * Returns whether the AI run was successful.
     *
     * @return {@code true} if the run succeeded
     */
    public boolean isSuccess() {
      return success;
    }

    /**
     * Returns the token usage for the run.
     *
     * @return the token usage, or {@code null} if not available
     */
    public TokenUsage getTokens() {
      return tokens;
    }

    /**
     * Returns the identifiers of tools invoked during the run.
     *
     * @return an unmodifiable list of tool keys, or {@code null} if none were recorded
     */
    public List<String> getToolCalls() {
      return toolCalls;
    }

    /**
     * Returns the measured duration of the run in milliseconds.
     *
     * @return the duration in milliseconds, or {@code null} if not measured
     */
    public Long getDurationMs() {
      return durationMs;
    }

    /**
     * Creates a builder for a metrics result.
     *
     * @param success whether the run was successful
     * @return a new {@link Builder}
     */
    public static Builder builder(boolean success) {
      return new Builder(success);
    }

    /**
     * Builder for {@link Metrics}.
     */
    public static final class Builder {
      private final boolean success;
      private TokenUsage tokens;
      private List<String> toolCalls;
      private Long durationMs;

      private Builder(boolean success) {
        this.success = success;
      }

      /**
       * Sets the token usage.
       *
       * @param v the token usage
       * @return this builder
       */
      public Builder tokens(TokenUsage v) {
        this.tokens = v;
        return this;
      }

      /**
       * Sets the tool-call identifiers.
       *
       * @param v the tool keys
       * @return this builder
       */
      public Builder toolCalls(List<String> v) {
        this.toolCalls = v;
        return this;
      }

      /**
       * Sets the measured duration in milliseconds.
       *
       * @param v the duration in milliseconds
       * @return this builder
       */
      public Builder durationMs(Long v) {
        this.durationMs = v;
        return this;
      }

      /**
       * Builds the immutable {@link Metrics}.
       *
       * @return a new {@link Metrics}
       */
      public Metrics build() {
        return new Metrics(this);
      }
    }
  }

  /**
   * The outcome of a judge evaluation, supplied to
   * {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker#trackJudgeResult}.
   * <p>
   * A {@code null} {@link #getScore() score} means "no score was produced" and is distinct from a
   * legitimate score of {@code 0.0}.
   */
  public static final class JudgeResult {
    private final String judgeConfigKey;
    private final boolean success;
    private final String errorMessage;
    private final boolean sampled;
    private final String metricKey;
    private final Double score;
    private final String reasoning;

    private JudgeResult(Builder b) {
      this.judgeConfigKey = b.judgeConfigKey;
      this.success = b.success;
      this.errorMessage = b.errorMessage;
      this.sampled = b.sampled;
      this.metricKey = b.metricKey;
      this.score = b.score;
      this.reasoning = b.reasoning;
    }

    /**
     * Returns the key of the judge configuration that produced this result.
     *
     * @return the judge config key, or {@code null} if not set
     */
    public String getJudgeConfigKey() {
      return judgeConfigKey;
    }

    /**
     * Returns whether the evaluation completed successfully.
     *
     * @return {@code true} if the evaluation succeeded
     */
    public boolean isSuccess() {
      return success;
    }

    /**
     * Returns the error message when the evaluation failed.
     *
     * @return the error message, or {@code null} if there was none
     */
    public String getErrorMessage() {
      return errorMessage;
    }

    /**
     * Returns whether the evaluation was sampled (actually run) rather than skipped.
     *
     * @return {@code true} if the evaluation was sampled
     */
    public boolean isSampled() {
      return sampled;
    }

    /**
     * Returns the metric key the score is reported against.
     *
     * @return the metric key, or {@code null} if not set
     */
    public String getMetricKey() {
      return metricKey;
    }

    /**
     * Returns the score, between {@code 0.0} and {@code 1.0}.
     *
     * @return the score, or {@code null} if no score was produced
     */
    public Double getScore() {
      return score;
    }

    /**
     * Returns the reasoning behind the score.
     *
     * @return the reasoning, or {@code null} if not provided
     */
    public String getReasoning() {
      return reasoning;
    }

    /**
     * Creates a builder for a judge result.
     *
     * @param sampled whether the evaluation was sampled
     * @param success whether the evaluation succeeded
     * @return a new {@link Builder}
     */
    public static Builder builder(boolean sampled, boolean success) {
      return new Builder(sampled, success);
    }

    /**
     * Builder for {@link JudgeResult}.
     */
    public static final class Builder {
      private final boolean sampled;
      private final boolean success;
      private String judgeConfigKey;
      private String errorMessage;
      private String metricKey;
      private Double score;
      private String reasoning;

      private Builder(boolean sampled, boolean success) {
        this.sampled = sampled;
        this.success = success;
      }

      /**
       * Sets the judge configuration key.
       *
       * @param v the judge config key
       * @return this builder
       */
      public Builder judgeConfigKey(String v) {
        this.judgeConfigKey = v;
        return this;
      }

      /**
       * Sets the error message.
       *
       * @param v the error message
       * @return this builder
       */
      public Builder errorMessage(String v) {
        this.errorMessage = v;
        return this;
      }

      /**
       * Sets the metric key.
       *
       * @param v the metric key
       * @return this builder
       */
      public Builder metricKey(String v) {
        this.metricKey = v;
        return this;
      }

      /**
       * Sets the score.
       *
       * @param v the score
       * @return this builder
       */
      public Builder score(Double v) {
        this.score = v;
        return this;
      }

      /**
       * Sets the reasoning.
       *
       * @param v the reasoning
       * @return this builder
       */
      public Builder reasoning(String v) {
        this.reasoning = v;
        return this;
      }

      /**
       * Builds the immutable {@link JudgeResult}.
       *
       * @return a new {@link JudgeResult}
       */
      public JudgeResult build() {
        return new JudgeResult(this);
      }
    }
  }

  /**
   * A snapshot summary of the metrics recorded on a tracker, returned by
   * {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker#getSummary}.
   * <p>
   * Fields are {@code null} when the corresponding metric was never recorded.
   */
  public static final class MetricSummary {
    private final Boolean success;
    private final TokenUsage tokens;
    private final List<String> toolCalls;
    private final Long durationMs;
    private final Long timeToFirstTokenMs;
    private final FeedbackKind feedback;
    private final String resumptionToken;

    private MetricSummary(Builder b) {
      this.success = b.success;
      this.tokens = b.tokens;
      this.toolCalls = b.toolCalls == null
          ? null : Collections.unmodifiableList(new ArrayList<>(b.toolCalls));
      this.durationMs = b.durationMs;
      this.timeToFirstTokenMs = b.timeToFirstTokenMs;
      this.feedback = b.feedback;
      this.resumptionToken = b.resumptionToken;
    }

    /**
     * Returns whether the run was recorded as successful.
     *
     * @return {@code true}/{@code false} once recorded, or {@code null} if neither success nor
     *     error was recorded
     */
    public Boolean getSuccess() {
      return success;
    }

    /**
     * Returns the recorded token usage.
     *
     * @return the token usage, or {@code null} if not recorded
     */
    public TokenUsage getTokens() {
      return tokens;
    }

    /**
     * Returns an immutable snapshot of the tool calls recorded so far.
     *
     * @return an unmodifiable list of tool keys, or {@code null} if none were recorded
     */
    public List<String> getToolCalls() {
      return toolCalls;
    }

    /**
     * Returns the recorded generation duration in milliseconds.
     *
     * @return the duration in milliseconds, or {@code null} if not recorded
     */
    public Long getDurationMs() {
      return durationMs;
    }

    /**
     * Returns the recorded time-to-first-token in milliseconds.
     *
     * @return the time to first token in milliseconds, or {@code null} if not recorded
     */
    public Long getTimeToFirstTokenMs() {
      return timeToFirstTokenMs;
    }

    /**
     * Returns the recorded feedback sentiment.
     *
     * @return the feedback, or {@code null} if not recorded
     */
    public FeedbackKind getFeedback() {
      return feedback;
    }

    /**
     * Returns the resumption token for the run this summary belongs to.
     *
     * @return the resumption token
     */
    public String getResumptionToken() {
      return resumptionToken;
    }

    /**
     * Creates a new builder.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Builder for {@link MetricSummary}.
     */
    public static final class Builder {
      private Boolean success;
      private TokenUsage tokens;
      private List<String> toolCalls;
      private Long durationMs;
      private Long timeToFirstTokenMs;
      private FeedbackKind feedback;
      private String resumptionToken;

      private Builder() {
      }

      /**
       * Sets the success flag.
       *
       * @param v the success flag
       * @return this builder
       */
      public Builder success(Boolean v) {
        this.success = v;
        return this;
      }

      /**
       * Sets the token usage.
       *
       * @param v the token usage
       * @return this builder
       */
      public Builder tokens(TokenUsage v) {
        this.tokens = v;
        return this;
      }

      /**
       * Sets the tool-call identifiers.
       *
       * @param v the tool keys
       * @return this builder
       */
      public Builder toolCalls(List<String> v) {
        this.toolCalls = v;
        return this;
      }

      /**
       * Sets the generation duration in milliseconds.
       *
       * @param v the duration in milliseconds
       * @return this builder
       */
      public Builder durationMs(Long v) {
        this.durationMs = v;
        return this;
      }

      /**
       * Sets the time-to-first-token in milliseconds.
       *
       * @param v the time to first token in milliseconds
       * @return this builder
       */
      public Builder timeToFirstTokenMs(Long v) {
        this.timeToFirstTokenMs = v;
        return this;
      }

      /**
       * Sets the feedback sentiment.
       *
       * @param v the feedback
       * @return this builder
       */
      public Builder feedback(FeedbackKind v) {
        this.feedback = v;
        return this;
      }

      /**
       * Sets the resumption token.
       *
       * @param v the resumption token
       * @return this builder
       */
      public Builder resumptionToken(String v) {
        this.resumptionToken = v;
        return this;
      }

      /**
       * Builds the immutable {@link MetricSummary}.
       *
       * @return a new {@link MetricSummary}
       */
      public MetricSummary build() {
        return new MetricSummary(this);
      }
    }
  }

  /**
   * The correlation data attached to every event a tracker emits.
   * <p>
   * All events for one AI run share a {@code runId} so LaunchDarkly can correlate them.
   */
  public static final class TrackData {
    private final String runId;
    private final String configKey;
    private final String variationKey;
    private final int version;
    private final String modelName;
    private final String providerName;
    private final String graphKey;

    /**
     * Creates a track-data record.
     *
     * @param runId the per-run UUID shared by all of the run's events
     * @param configKey the AI Config key
     * @param variationKey the variation key, or empty string when unknown
     * @param version the AI Config version
     * @param modelName the model name, or empty string when unknown
     * @param providerName the provider name, or empty string when unknown
     * @param graphKey the graph key, or {@code null} when not part of a graph
     */
    public TrackData(
        String runId,
        String configKey,
        String variationKey,
        int version,
        String modelName,
        String providerName,
        String graphKey) {
      this.runId = runId;
      this.configKey = configKey;
      this.variationKey = variationKey;
      this.version = version;
      this.modelName = modelName;
      this.providerName = providerName;
      this.graphKey = graphKey;
    }

    /**
     * Returns the per-run UUID shared by all of the run's events.
     *
     * @return the run id
     */
    public String getRunId() {
      return runId;
    }

    /**
     * Returns the AI Config key.
     *
     * @return the config key
     */
    public String getConfigKey() {
      return configKey;
    }

    /**
     * Returns the variation key.
     *
     * @return the variation key, or empty string when unknown
     */
    public String getVariationKey() {
      return variationKey;
    }

    /**
     * Returns the AI Config version.
     *
     * @return the version
     */
    public int getVersion() {
      return version;
    }

    /**
     * Returns the model name.
     *
     * @return the model name, or empty string when unknown
     */
    public String getModelName() {
      return modelName;
    }

    /**
     * Returns the provider name.
     *
     * @return the provider name, or empty string when unknown
     */
    public String getProviderName() {
      return providerName;
    }

    /**
     * Returns the graph key.
     *
     * @return the graph key, or {@code null} when not part of a graph
     */
    public String getGraphKey() {
      return graphKey;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TrackData)) {
        return false;
      }
      TrackData that = (TrackData) o;
      return version == that.version
          && Objects.equals(runId, that.runId)
          && Objects.equals(configKey, that.configKey)
          && Objects.equals(variationKey, that.variationKey)
          && Objects.equals(modelName, that.modelName)
          && Objects.equals(providerName, that.providerName)
          && Objects.equals(graphKey, that.graphKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(runId, configKey, variationKey, version, modelName, providerName, graphKey);
    }

    @Override
    public String toString() {
      return "TrackData{runId=" + runId + ", configKey=" + configKey + ", variationKey=" + variationKey
          + ", version=" + version + ", modelName=" + modelName + ", providerName=" + providerName
          + ", graphKey=" + graphKey + '}';
    }
  }
}
