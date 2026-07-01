package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The result of a single {@link Runner} invocation.
 * <p>
 * Instances are immutable. Build them with {@link #builder(String, AIMetrics)}.
 */
public final class RunnerResult {
  private final String content;
  private final AIMetrics metrics;
  private final Object raw;
  private final Map<String, Object> parsed;

  private RunnerResult(Builder b) {
    this.content = b.content;
    this.metrics = b.metrics;
    this.raw = b.raw;
    this.parsed = b.parsed == null ? null : Collections.unmodifiableMap(new HashMap<>(b.parsed));
  }

  /**
   * Returns the text content of the model response.
   *
   * @return the response text, or {@code null} if none was produced
   */
  public String getContent() {
    return content;
  }

  /**
   * Returns the metrics captured during this invocation.
   *
   * @return the metrics, never {@code null}
   */
  public AIMetrics getMetrics() {
    return metrics;
  }

  /**
   * Returns the unmodified provider response object, useful for provider-specific inspection.
   *
   * @return the raw response, or {@code null} if not set
   */
  public Object getRaw() {
    return raw;
  }

  /**
   * Returns the structured output parsed from the model response, when the runner was invoked with
   * an {@code outputType} schema.
   *
   * @return an unmodifiable map of the structured output, or {@code null} if not set
   */
  public Map<String, Object> getParsed() {
    return parsed;
  }

  /**
   * Creates a builder for a {@link RunnerResult}.
   *
   * @param content the text content of the model response; may be {@code null}
   * @param metrics the metrics for this invocation; must not be {@code null}
   * @return a new {@link Builder}
   */
  public static Builder builder(String content, AIMetrics metrics) {
    return new Builder(content, metrics);
  }

  /**
   * Builder for {@link RunnerResult}.
   */
  public static final class Builder {
    private final String content;
    private final AIMetrics metrics;
    private Object raw;
    private Map<String, Object> parsed;

    private Builder(String content, AIMetrics metrics) {
      this.content = content;
      this.metrics = metrics;
    }

    /**
     * Sets the unmodified provider response.
     *
     * @param raw the raw response object; may be {@code null}
     * @return this builder
     */
    public Builder raw(Object raw) {
      this.raw = raw;
      return this;
    }

    /**
     * Sets the structured output parsed from the model response.
     *
     * @param parsed the structured output map; may be {@code null}
     * @return this builder
     */
    public Builder parsed(Map<String, Object> parsed) {
      this.parsed = parsed;
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
