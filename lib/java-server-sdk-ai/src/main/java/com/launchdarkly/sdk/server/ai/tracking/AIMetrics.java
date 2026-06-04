package com.launchdarkly.sdk.server.ai.tracking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A standardized view of the outcome of an AI operation, used by
 * {@link AIConfigTracker#trackMetricsOf(java.util.function.Function, java.util.function.Supplier)}
 * to drive automatic tracking.
 */
public final class AIMetrics {
  private final boolean success;
  private final TokenUsage tokens;
  private final Long durationMs;
  private final List<String> toolCalls;

  private AIMetrics(Builder builder) {
    this.success = builder.success;
    this.tokens = builder.tokens;
    this.durationMs = builder.durationMs;
    this.toolCalls = builder.toolCalls == null ? null : Collections.unmodifiableList(builder.toolCalls);
  }

  /**
   * Returns whether the AI operation was successful.
   *
   * @return {@code true} if successful
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * Returns the token usage for the operation.
   *
   * @return the token usage, or {@code null} if unknown
   */
  public TokenUsage getTokens() {
    return tokens;
  }

  /**
   * Returns the measured duration of the operation.
   * <p>
   * When present, this value is used instead of the wall-clock time measured by the tracker.
   *
   * @return the duration in milliseconds, or {@code null} if unknown
   */
  public Long getDurationMs() {
    return durationMs;
  }

  /**
   * Returns the tool calls made during the operation.
   *
   * @return an unmodifiable list of tool keys, or {@code null} if not reported
   */
  public List<String> getToolCalls() {
    return toolCalls;
  }

  /**
   * Creates a builder for an {@link AIMetrics} with the given success status.
   *
   * @param success whether the operation was successful
   * @return a new builder
   */
  public static Builder builder(boolean success) {
    return new Builder(success);
  }

  /**
   * A builder for {@link AIMetrics} instances.
   */
  public static final class Builder {
    private final boolean success;
    private TokenUsage tokens;
    private Long durationMs;
    private List<String> toolCalls;

    private Builder(boolean success) {
      this.success = success;
    }

    /** @param tokens the token usage @return this builder */
    public Builder tokens(TokenUsage tokens) {
      this.tokens = tokens;
      return this;
    }

    /** @param durationMs the measured duration in milliseconds @return this builder */
    public Builder durationMs(Long durationMs) {
      this.durationMs = durationMs;
      return this;
    }

    /** @param toolCalls the tool keys invoked during the operation @return this builder */
    public Builder toolCalls(List<String> toolCalls) {
      this.toolCalls = toolCalls == null ? null : new ArrayList<>(toolCalls);
      return this;
    }

    /**
     * Builds the metrics.
     *
     * @return a new {@link AIMetrics}
     */
    public AIMetrics build() {
      return new AIMetrics(this);
    }
  }
}
