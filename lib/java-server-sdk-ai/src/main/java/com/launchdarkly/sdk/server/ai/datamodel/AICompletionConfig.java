package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.server.ai.evaluation.Evaluator;
import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A traditional ("completion" mode) AI Config, composed of chat-style messages.
 * <p>
 * Instances are produced by {@code LDAIClient.completionConfig}; application code does not normally
 * construct them directly.
 */
public final class AICompletionConfig extends AIConfig {
  private final List<LDMessage> messages;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, LDTool> tools;
  private final Evaluator evaluator;

  private AICompletionConfig(Builder builder) {
    super(builder.key, builder.enabled, builder.model, builder.provider, builder.trackerFactory);
    this.messages = builder.messages == null ? null : Collections.unmodifiableList(builder.messages);
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools == null ? null : Collections.unmodifiableMap(builder.tools);
    this.evaluator = builder.evaluator == null ? Evaluator.noop() : builder.evaluator;
  }

  /**
   * Returns the prompt messages, with their content already interpolated.
   *
   * @return the messages, or {@code null} if none were provided
   */
  public List<LDMessage> getMessages() {
    return messages;
  }

  /**
   * Returns the judge configuration attached to this config.
   *
   * @return the judge configuration, or {@code null} if none was provided
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the root-level tools map.
   *
   * @return an unmodifiable map of tool name to tool, or {@code null} if no tools were provided
   */
  public Map<String, LDTool> getTools() {
    return tools;
  }

  /**
   * Returns the evaluator built from this config's judge configuration.
   * <p>
   * When no judges are configured, this is a no-op evaluator that produces an empty result.
   *
   * @return the evaluator (never {@code null})
   */
  public Evaluator getEvaluator() {
    return evaluator;
  }

  /**
   * Creates a builder for an {@link AICompletionConfig}.
   *
   * @param key the configuration key
   * @return a new builder
   */
  public static Builder builder(String key) {
    return new Builder(key);
  }

  /**
   * A builder for {@link AICompletionConfig} instances.
   */
  public static final class Builder {
    private final String key;
    private boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private Supplier<AIConfigTracker> trackerFactory;
    private List<LDMessage> messages;
    private JudgeConfiguration judgeConfiguration;
    private Map<String, LDTool> tools;
    private Evaluator evaluator;

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

    /** @param messages the interpolated prompt messages @return this builder */
    public Builder messages(List<LDMessage> messages) {
      this.messages = messages;
      return this;
    }

    /** @param judgeConfiguration the judge configuration @return this builder */
    public Builder judgeConfiguration(JudgeConfiguration judgeConfiguration) {
      this.judgeConfiguration = judgeConfiguration;
      return this;
    }

    /** @param tools the root-level tools map @return this builder */
    public Builder tools(Map<String, LDTool> tools) {
      this.tools = tools;
      return this;
    }

    /** @param evaluator the evaluator built from the judge configuration @return this builder */
    public Builder evaluator(Evaluator evaluator) {
      this.evaluator = evaluator;
      return this;
    }

    /**
     * Builds the completion config.
     *
     * @return a new {@link AICompletionConfig}
     */
    public AICompletionConfig build() {
      return new AICompletionConfig(this);
    }
  }
}
