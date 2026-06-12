package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A caller-supplied default for {@link LDAIClient#judgeConfig}, returned (as an
 * {@link AIJudgeConfig}) when the flag is absent or cannot be evaluated.
 * <p>
 * Build instances with {@link #builder()}. Instances are immutable.
 */
public final class AIJudgeConfigDefault extends AIConfigDefault {
  private final List<Message> messages;
  private final String evaluationMetricKey;

  private AIJudgeConfigDefault(Builder builder) {
    super(builder);
    this.messages = builder.messages == null
        ? null : Collections.unmodifiableList(new ArrayList<>(builder.messages));
    this.evaluationMetricKey = builder.evaluationMetricKey;
  }

  /**
   * Returns the default prompt messages.
   *
   * @return an unmodifiable list of messages, or {@code null} if none were specified
   */
  public List<Message> getMessages() {
    return messages;
  }

  /**
   * Returns the default evaluation metric key.
   *
   * @return the evaluation metric key, or {@code null} if none was specified
   */
  public String getEvaluationMetricKey() {
    return evaluationMetricKey;
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
   * Returns a disabled default, suitable as a fallback that causes callers to skip the model.
   *
   * @return a disabled {@link AIJudgeConfigDefault}
   */
  public static AIJudgeConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Builder for {@link AIJudgeConfigDefault}.
   */
  public static final class Builder extends AbstractBuilder<Builder> {
    private List<Message> messages;
    private String evaluationMetricKey;

    private Builder() {
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the default prompt messages. The list is copied defensively.
     *
     * @param messages the messages; may be {@code null}
     * @return this builder
     */
    public Builder messages(List<Message> messages) {
      this.messages = messages;
      return this;
    }

    /**
     * Sets the default evaluation metric key.
     *
     * @param evaluationMetricKey the evaluation metric key; may be {@code null}
     * @return this builder
     */
    public Builder evaluationMetricKey(String evaluationMetricKey) {
      this.evaluationMetricKey = evaluationMetricKey;
      return this;
    }

    /**
     * Builds the immutable {@link AIJudgeConfigDefault}.
     *
     * @return a new {@link AIJudgeConfigDefault}
     */
    public AIJudgeConfigDefault build() {
      return new AIJudgeConfigDefault(this);
    }
  }
}
