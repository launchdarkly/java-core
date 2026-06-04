package com.launchdarkly.sdk.server.ai.evaluation;

/**
 * The outcome of a single judge metric evaluation.
 * <p>
 * When {@link #isSampled()} is {@code false}, the evaluation was bypassed by the judge's sampling
 * rate and the remaining fields are at their default values and should not be treated as results.
 */
public final class JudgeResult {
  private final String judgeConfigKey;
  private final boolean success;
  private final String errorMessage;
  private final boolean sampled;
  private final String metricKey;
  private final Double score;
  private final String reasoning;

  private JudgeResult(Builder builder) {
    this.judgeConfigKey = builder.judgeConfigKey;
    this.success = builder.success;
    this.errorMessage = builder.errorMessage;
    this.sampled = builder.sampled;
    this.metricKey = builder.metricKey;
    this.score = builder.score;
    this.reasoning = builder.reasoning;
  }

  /**
   * Returns a result indicating the evaluation was skipped by the sampling rate.
   *
   * @return a not-sampled result
   */
  public static JudgeResult notSampled() {
    return builder().sampled(false).success(false).build();
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
   * @return {@code true} if successful
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * Returns the error message if the evaluation failed.
   *
   * @return the error message, or {@code null}
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * Returns whether the evaluation was sampled and executed.
   *
   * @return {@code true} if the evaluation was performed
   */
  public boolean isSampled() {
    return sampled;
  }

  /**
   * Returns the metric key this result corresponds to.
   *
   * @return the metric key, or {@code null}
   */
  public String getMetricKey() {
    return metricKey;
  }

  /**
   * Returns the evaluation score, between {@code 0.0} and {@code 1.0}.
   *
   * @return the score, or {@code null} if not available
   */
  public Double getScore() {
    return score;
  }

  /**
   * Returns the reasoning behind the score.
   *
   * @return the reasoning, or {@code null}
   */
  public String getReasoning() {
    return reasoning;
  }

  /**
   * Creates a builder for a {@link JudgeResult}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link JudgeResult} instances.
   */
  public static final class Builder {
    private String judgeConfigKey;
    private boolean success = false;
    private String errorMessage;
    private boolean sampled = false;
    private String metricKey;
    private Double score;
    private String reasoning;

    private Builder() {
    }

    /** @param judgeConfigKey the judge config key @return this builder */
    public Builder judgeConfigKey(String judgeConfigKey) {
      this.judgeConfigKey = judgeConfigKey;
      return this;
    }

    /** @param success whether the evaluation succeeded @return this builder */
    public Builder success(boolean success) {
      this.success = success;
      return this;
    }

    /** @param errorMessage the error message @return this builder */
    public Builder errorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    /** @param sampled whether the evaluation was sampled and executed @return this builder */
    public Builder sampled(boolean sampled) {
      this.sampled = sampled;
      return this;
    }

    /** @param metricKey the metric key @return this builder */
    public Builder metricKey(String metricKey) {
      this.metricKey = metricKey;
      return this;
    }

    /** @param score the evaluation score @return this builder */
    public Builder score(Double score) {
      this.score = score;
      return this;
    }

    /** @param reasoning the reasoning behind the score @return this builder */
    public Builder reasoning(String reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    /**
     * Builds the result.
     *
     * @return a new {@link JudgeResult}
     */
    public JudgeResult build() {
      return new JudgeResult(this);
    }
  }
}
