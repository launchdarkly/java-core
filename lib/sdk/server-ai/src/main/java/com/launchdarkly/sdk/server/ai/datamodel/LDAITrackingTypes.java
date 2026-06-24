package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Container for the shared, immutable AI tracking data types.
 * <p>
 * These shapes ({@link FeedbackKind}, {@link TokenUsage}, {@link AIMetrics}, {@link JudgeResult},
 * {@link MetricSummary}, and {@link TrackData}) are used by {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker}
 * and its implementations to report AI run metrics and feedback. They are grouped under this single
 * type, rather than declared as separate top-level classes, to keep the package small and to free
 * up generic names.
 * <p>
 * This class is not instantiable.
 */
public final class LDAITrackingTypes {
  private LDAITrackingTypes() {
  }

  /**
   * The kind of user feedback reported via {@code trackFeedback}.
   */
  public enum FeedbackKind {
    /**
     * Positive (thumbs-up) feedback.
     */
    POSITIVE("positive"),

    /**
     * Negative (thumbs-down) feedback.
     */
    NEGATIVE("negative");

    private final String value;

    FeedbackKind(String value) {
      this.value = value;
    }

    /**
     * Returns the wire representation of this feedback kind.
     *
     * @return the wire value (for example {@code "positive"})
     */
    public String getValue() {
      return value;
    }
  }

  /**
   * Token usage counts for a single AI generation.
   * <p>
   * Instances are immutable.
   */
  public static final class TokenUsage {
    private final long total;
    private final long input;
    private final long output;

    /**
     * Constructs token usage counts.
     *
     * @param total the total token count
     * @param input the input (prompt) token count
     * @param output the output (completion) token count
     */
    public TokenUsage(long total, long input, long output) {
      this.total = total;
      this.input = input;
      this.output = output;
    }

    /**
     * Returns the total token count.
     *
     * @return the total token count
     */
    public long getTotal() {
      return total;
    }

    /**
     * Returns the input (prompt) token count.
     *
     * @return the input token count
     */
    public long getInput() {
      return input;
    }

    /**
     * Returns the output (completion) token count.
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
      TokenUsage other = (TokenUsage) o;
      return total == other.total && input == other.input && output == other.output;
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
   * Metrics extracted from a single AI generation, used with {@code trackMetricsOf}.
   * <p>
   * Build instances with {@link #builder()}.
   * <p>
   * Instances are immutable.
   */
  public static final class AIMetrics {
    private final boolean success;
    private final TokenUsage tokens;
    private final List<String> toolCalls;
    private final Long durationMs;

    private AIMetrics(Builder b) {
      this.success = b.success;
      this.tokens = b.tokens;
      this.toolCalls = b.toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(b.toolCalls));
      this.durationMs = b.durationMs;
    }

    /**
     * Returns whether the AI generation succeeded.
     *
     * @return {@code true} if the generation succeeded
     */
    public boolean isSuccess() {
      return success;
    }

    /**
     * Returns the token usage for this generation.
     *
     * @return the token usage, or {@code null} if not reported
     */
    public TokenUsage getTokens() {
      return tokens;
    }

    /**
     * Returns the tool calls made during this generation.
     *
     * @return an unmodifiable list of tool call keys, or {@code null} if not reported
     */
    public List<String> getToolCalls() {
      return toolCalls;
    }

    /**
     * Returns the duration of the AI generation in milliseconds, as reported by the runner.
     * <p>
     * When set, {@code trackMetricsOf} uses this value instead of its own wall-clock measurement.
     *
     * @return the runner-reported duration in milliseconds, or {@code null} if not reported
     */
    public Long getDurationMs() {
      return durationMs;
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
     * Builder for {@link AIMetrics}.
     */
    public static final class Builder {
      private boolean success;
      private TokenUsage tokens;
      private List<String> toolCalls;
      private Long durationMs;

      private Builder() {
      }

      /**
       * Sets whether the AI generation succeeded.
       *
       * @param success {@code true} if the generation succeeded
       * @return this builder
       */
      public Builder success(boolean success) {
        this.success = success;
        return this;
      }

      /**
       * Sets the token usage.
       *
       * @param tokens the token usage; may be {@code null}
       * @return this builder
       */
      public Builder tokens(TokenUsage tokens) {
        this.tokens = tokens;
        return this;
      }

      /**
       * Sets the tool calls made during this generation.
       *
       * @param toolCalls the tool call keys; may be {@code null}
       * @return this builder
       */
      public Builder toolCalls(List<String> toolCalls) {
        this.toolCalls = toolCalls;
        return this;
      }

      /**
       * Sets the runner-reported duration in milliseconds.
       *
       * @param durationMs the duration; may be {@code null}
       * @return this builder
       */
      public Builder durationMs(Long durationMs) {
        this.durationMs = durationMs;
        return this;
      }

      /**
       * Builds the immutable {@link AIMetrics}.
       *
       * @return a new {@link AIMetrics}
       */
      public AIMetrics build() {
        return new AIMetrics(this);
      }
    }
  }

  /**
   * The result of a judge evaluation, reported via {@code trackJudgeResult}.
   * <p>
   * Build instances with {@link #builder()}.
   * <p>
   * Instances are immutable.
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
     * Returns the key of the judge AI Config, if known.
     *
     * @return the judge config key, or {@code null} if not set
     */
    public String getJudgeConfigKey() {
      return judgeConfigKey;
    }

    /**
     * Returns whether the judge evaluation succeeded.
     *
     * @return {@code true} if the evaluation succeeded
     */
    public boolean isSuccess() {
      return success;
    }

    /**
     * Returns an error message from the judge evaluation, if any.
     *
     * @return the error message, or {@code null} if none
     */
    public String getErrorMessage() {
      return errorMessage;
    }

    /**
     * Returns whether this result was selected for sampling.
     *
     * @return {@code true} if the result was sampled
     */
    public boolean isSampled() {
      return sampled;
    }

    /**
     * Returns the metric key to use when emitting this result.
     *
     * @return the metric key, or {@code null} if not set
     */
    public String getMetricKey() {
      return metricKey;
    }

    /**
     * Returns the judge score.
     * <p>
     * A {@code null} score is distinct from a score of {@code 0.0} — a null score means no score
     * was produced, while {@code 0.0} is a valid score.
     *
     * @return the score, or {@code null} if not set
     */
    public Double getScore() {
      return score;
    }

    /**
     * Returns the judge's reasoning, if any.
     *
     * @return the reasoning, or {@code null} if none
     */
    public String getReasoning() {
      return reasoning;
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
     * Builder for {@link JudgeResult}.
     */
    public static final class Builder {
      private String judgeConfigKey;
      private boolean success;
      private String errorMessage;
      private boolean sampled;
      private String metricKey;
      private Double score;
      private String reasoning;

      private Builder() {
      }

      /**
       * Sets the judge config key.
       *
       * @param judgeConfigKey the key; may be {@code null}
       * @return this builder
       */
      public Builder judgeConfigKey(String judgeConfigKey) {
        this.judgeConfigKey = judgeConfigKey;
        return this;
      }

      /**
       * Sets whether the judge evaluation succeeded.
       *
       * @param success {@code true} if succeeded
       * @return this builder
       */
      public Builder success(boolean success) {
        this.success = success;
        return this;
      }

      /**
       * Sets the error message.
       *
       * @param errorMessage the error message; may be {@code null}
       * @return this builder
       */
      public Builder errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        return this;
      }

      /**
       * Sets whether this result was sampled.
       *
       * @param sampled {@code true} if sampled
       * @return this builder
       */
      public Builder sampled(boolean sampled) {
        this.sampled = sampled;
        return this;
      }

      /**
       * Sets the metric key.
       *
       * @param metricKey the metric key; may be {@code null}, but must not be blank if non-null
       * @return this builder
       * @throws IllegalArgumentException if {@code metricKey} is non-null and blank
       */
      public Builder metricKey(String metricKey) {
        if (metricKey != null && metricKey.trim().isEmpty()) {
          throw new IllegalArgumentException("metricKey must not be blank");
        }
        this.metricKey = metricKey;
        return this;
      }

      /**
       * Sets the judge score.
       *
       * @param score the score; may be {@code null}, but must be finite if non-null
       * @return this builder
       * @throws IllegalArgumentException if {@code score} is non-null and non-finite (NaN or infinite)
       */
      public Builder score(Double score) {
        if (score != null && !Double.isFinite(score)) {
          throw new IllegalArgumentException("score must be finite");
        }
        this.score = score;
        return this;
      }

      /**
       * Sets the reasoning.
       *
       * @param reasoning the reasoning; may be {@code null}
       * @return this builder
       */
      public Builder reasoning(String reasoning) {
        this.reasoning = reasoning;
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
   * A snapshot of all metrics tracked by a single {@link com.launchdarkly.sdk.server.ai.LDAIConfigTracker}.
   * <p>
   * Returned by {@code getSummary()}. All fields are nullable — {@code null} indicates the
   * corresponding metric has not been recorded yet. {@link #getSuccess()} is a tri-state:
   * {@code null} = not yet tracked, {@code true} = success was recorded, {@code false} = error
   * was recorded.
   * <p>
   * Instances are immutable.
   */
  public static final class MetricSummary {
    private final Boolean success;
    private final TokenUsage tokens;
    private final Long durationMs;
    private final FeedbackKind feedback;
    private final Long timeToFirstTokenMs;
    private final List<String> toolCalls;
    private final String resumptionToken;

    /**
     * Constructs a metric summary snapshot.
     *
     * @param success tri-state outcome: {@code null} = not tracked, {@code true} = success, {@code false} = error
     * @param tokens the token usage, or {@code null}
     * @param durationMs the duration in milliseconds, or {@code null}
     * @param feedback the feedback kind, or {@code null}
     * @param timeToFirstTokenMs the time to first token in milliseconds, or {@code null}
     * @param toolCalls the tool calls made, or {@code null}
     * @param resumptionToken the resumption token, or {@code null}
     */
    public MetricSummary(
        Boolean success,
        TokenUsage tokens,
        Long durationMs,
        FeedbackKind feedback,
        Long timeToFirstTokenMs,
        List<String> toolCalls,
        String resumptionToken) {
      this.success = success;
      this.tokens = tokens;
      this.durationMs = durationMs;
      this.feedback = feedback;
      this.timeToFirstTokenMs = timeToFirstTokenMs;
      this.toolCalls = toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(toolCalls));
      this.resumptionToken = resumptionToken;
    }

    /**
     * Returns the outcome of the AI generation, as a tri-state.
     *
     * @return {@code null} if not tracked, {@code true} if success was recorded, {@code false} if error was recorded
     */
    public Boolean getSuccess() {
      return success;
    }

    /**
     * Returns the token usage.
     *
     * @return the token usage, or {@code null} if not tracked
     */
    public TokenUsage getTokens() {
      return tokens;
    }

    /**
     * Returns the duration in milliseconds.
     *
     * @return the duration, or {@code null} if not tracked
     */
    public Long getDurationMs() {
      return durationMs;
    }

    /**
     * Returns the feedback kind.
     *
     * @return the feedback, or {@code null} if not tracked
     */
    public FeedbackKind getFeedback() {
      return feedback;
    }

    /**
     * Returns the time to first token in milliseconds.
     *
     * @return the time to first token, or {@code null} if not tracked
     */
    public Long getTimeToFirstTokenMs() {
      return timeToFirstTokenMs;
    }

    /**
     * Returns the tool calls made during the generation.
     *
     * @return an unmodifiable list of tool call keys, or {@code null} if none were tracked
     */
    public List<String> getToolCalls() {
      return toolCalls;
    }

    /**
     * Returns the resumption token for this tracker.
     * <p>
     * <strong>Security note:</strong> resumption tokens embed flag-evaluation details such as the
     * variation key and config version. Keep tokens server-side and do not round-trip them through
     * untrusted clients where they could leak flag-targeting information.
     *
     * @return the resumption token, or {@code null} if not available
     */
    public String getResumptionToken() {
      return resumptionToken;
    }
  }

  /**
   * Correlation metadata attached to every metric event emitted by a tracker.
   * <p>
   * Instances are immutable.
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
     * Constructs track data.
     *
     * @param runId the unique run identifier; must not be {@code null}
     * @param configKey the AI Config key; must not be {@code null}
     * @param variationKey the variation key, or {@code null} when a default config is used
     * @param version the config version
     * @param modelName the model name, or empty string when unknown
     * @param providerName the provider name, or empty string when unknown
     * @param graphKey the agent graph key, or {@code null} when not part of a graph
     */
    public TrackData(
        String runId,
        String configKey,
        String variationKey,
        int version,
        String modelName,
        String providerName,
        String graphKey) {
      this.runId = Objects.requireNonNull(runId, "runId");
      this.configKey = Objects.requireNonNull(configKey, "configKey");
      this.variationKey = variationKey;
      this.version = version;
      this.modelName = modelName == null ? "" : modelName;
      this.providerName = providerName == null ? "" : providerName;
      this.graphKey = graphKey;
    }

    /**
     * Returns the unique run identifier.
     *
     * @return the run ID, never {@code null}
     */
    public String getRunId() {
      return runId;
    }

    /**
     * Returns the AI Config key.
     *
     * @return the config key, never {@code null}
     */
    public String getConfigKey() {
      return configKey;
    }

    /**
     * Returns the variation key.
     *
     * @return the variation key, or {@code null} when a default config is used
     */
    public String getVariationKey() {
      return variationKey;
    }

    /**
     * Returns the config version.
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
     * Returns the agent graph key.
     *
     * @return the graph key, or {@code null} when not part of a graph
     */
    public String getGraphKey() {
      return graphKey;
    }

    /**
     * Builds an {@link LDValue} representation of this track data using camelCase keys.
     * <p>
     * {@code variationKey} and {@code graphKey} are omitted when {@code null}.
     *
     * @return an {@link LDValue} object containing all non-null fields
     */
    public LDValue toLDValue() {
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
      return b.build();
    }
  }
}
