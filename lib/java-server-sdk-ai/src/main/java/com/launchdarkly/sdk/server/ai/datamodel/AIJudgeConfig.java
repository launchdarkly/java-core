package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A judge AI Config ("judge" mode), used to evaluate AI outputs. It is composed of chat-style
 * evaluation messages and a required evaluation metric key.
 * <p>
 * Instances are produced by {@code LDAIClient.judgeConfig}; application code does not normally
 * construct them directly.
 */
public final class AIJudgeConfig extends AIConfig {
  private final List<LDMessage> messages;
  private final String evaluationMetricKey;

  private AIJudgeConfig(Builder builder) {
    super(builder.key, builder.enabled, builder.model, builder.provider, builder.trackerFactory);
    this.messages = builder.messages == null ? null : Collections.unmodifiableList(builder.messages);
    this.evaluationMetricKey = builder.evaluationMetricKey;
  }

  /**
   * Returns the evaluation prompt messages, with their content already interpolated.
   *
   * @return the messages, or {@code null} if none were provided
   */
  public List<LDMessage> getMessages() {
    return messages;
  }

  /**
   * Returns the metric key that this judge evaluates.
   *
   * @return the evaluation metric key, or {@code null} if none was provided
   */
  public String getEvaluationMetricKey() {
    return evaluationMetricKey;
  }

  /**
   * Creates a builder for an {@link AIJudgeConfig}.
   *
   * @param key the configuration key
   * @return a new builder
   */
  public static Builder builder(String key) {
    return new Builder(key);
  }

  /**
   * A builder for {@link AIJudgeConfig} instances.
   */
  public static final class Builder {
    private final String key;
    private boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private Supplier<AIConfigTracker> trackerFactory;
    private List<LDMessage> messages;
    private String evaluationMetricKey;

    private Builder(String key) {
      this.key = key;
    }

    /** @param enabled whether the config is enabled @return this builder */
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

    /** @param trackerFactory the per-invocation tracker factory @return this builder */
    public Builder trackerFactory(Supplier<AIConfigTracker> trackerFactory) {
      this.trackerFactory = trackerFactory;
      return this;
    }

    /** @param messages the interpolated evaluation messages @return this builder */
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
     * Builds the judge config.
     *
     * @return a new {@link AIJudgeConfig}
     */
    public AIJudgeConfig build() {
      return new AIJudgeConfig(this);
    }
  }
}
