package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.List;

/**
 * A user-constructed default value for {@code LDAIClient.judgeConfig}.
 */
public final class AIJudgeConfigDefault extends AIConfigDefault {
  private final List<LDMessage> messages;
  private final String evaluationMetricKey;

  private AIJudgeConfigDefault(Builder builder) {
    super(builder.enabled, builder.model, builder.provider);
    this.messages = builder.messages;
    this.evaluationMetricKey = builder.evaluationMetricKey;
  }

  /**
   * Returns a disabled default.
   *
   * @return a default with {@code enabled} set to {@code false}
   */
  public static AIJudgeConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Returns the default evaluation messages.
   *
   * @return the messages, or {@code null} if none were provided
   */
  public List<LDMessage> getMessages() {
    return messages;
  }

  /**
   * Returns the default evaluation metric key.
   *
   * @return the evaluation metric key, or {@code null} if none was provided
   */
  public String getEvaluationMetricKey() {
    return evaluationMetricKey;
  }

  @Override
  public LDValue toLDValue() {
    ObjectBuilder builder = baseObject();
    builder.put("messages", AICompletionConfigDefault.messagesToLDValue(messages));
    builder.put("evaluationMetricKey",
        evaluationMetricKey == null ? LDValue.ofNull() : LDValue.of(evaluationMetricKey));
    return builder.build();
  }

  /**
   * Creates a builder for an {@link AIJudgeConfigDefault}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link AIJudgeConfigDefault} instances.
   */
  public static final class Builder {
    private Boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private List<LDMessage> messages;
    private String evaluationMetricKey;

    private Builder() {
    }

    /** @param enabled whether the config should be considered enabled @return this builder */
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /** @param model the model configuration @return this builder */
    public Builder model(ModelConfig model) {
      this.model = model;
      return this;
    }

    /** @param provider the provider configuration @return this builder */
    public Builder provider(ProviderConfig provider) {
      this.provider = provider;
      return this;
    }

    /** @param messages the default evaluation messages @return this builder */
    public Builder messages(List<LDMessage> messages) {
      this.messages = messages;
      return this;
    }

    /** @param evaluationMetricKey the metric key this judge evaluates @return this builder */
    public Builder evaluationMetricKey(String evaluationMetricKey) {
      this.evaluationMetricKey = evaluationMetricKey;
      return this;
    }

    /**
     * Builds the default.
     *
     * @return a new {@link AIJudgeConfigDefault}
     */
    public AIJudgeConfigDefault build() {
      return new AIJudgeConfigDefault(this);
    }
  }
}
