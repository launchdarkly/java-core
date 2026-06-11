package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;

/**
 * The result of a {@link Runner} invocation.
 * <p>
 * Carries the model's text {@link #getContent() content}, the {@link #getMetrics() metrics} the SDK
 * uses to track the run, and any {@link #getParsed() parsed} structured output. For judge
 * evaluation the parsed value is expected to be a JSON object with {@code score} (a number in
 * {@code [0.0, 1.0]}) and {@code reasoning} (a string). Instances are immutable.
 */
public final class RunnerResult {
  private final String content;
  private final Metrics metrics;
  private final LDValue parsed;

  private RunnerResult(Builder b) {
    this.content = b.content;
    this.metrics = b.metrics;
    this.parsed = b.parsed == null ? LDValue.ofNull() : b.parsed;
  }

  /**
   * Returns the model's text response.
   *
   * @return the content, or {@code null} if none was produced
   */
  public String getContent() {
    return content;
  }

  /**
   * Returns the metrics for this invocation.
   *
   * @return the metrics, or {@code null} if none were provided
   */
  public Metrics getMetrics() {
    return metrics;
  }

  /**
   * Returns the parsed structured output.
   *
   * @return the parsed value; never {@code null}, but {@link LDValue#ofNull()} when there was none
   */
  public LDValue getParsed() {
    return parsed;
  }

  /**
   * Creates a builder.
   *
   * @param metrics the metrics for the invocation
   * @return a new {@link Builder}
   */
  public static Builder builder(Metrics metrics) {
    return new Builder(metrics);
  }

  /**
   * Builder for {@link RunnerResult}.
   */
  public static final class Builder {
    private final Metrics metrics;
    private String content;
    private LDValue parsed;

    private Builder(Metrics metrics) {
      this.metrics = metrics;
    }

    /**
     * Sets the model's text response.
     *
     * @param v the content
     * @return this builder
     */
    public Builder content(String v) {
      this.content = v;
      return this;
    }

    /**
     * Sets the parsed structured output.
     *
     * @param v the parsed value
     * @return this builder
     */
    public Builder parsed(LDValue v) {
      this.parsed = v;
      return this;
    }

    /**
     * Builds the immutable {@link RunnerResult}.
     *
     * @return a new {@link RunnerResult}
     */
    public RunnerResult build() {
      return new RunnerResult(this);
    }
  }
}
