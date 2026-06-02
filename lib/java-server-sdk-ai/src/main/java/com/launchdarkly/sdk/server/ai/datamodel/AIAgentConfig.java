package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.server.ai.evaluation.Evaluator;
import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * An AI Config agent ("agent" mode), composed of freeform instructions rather than chat messages.
 * <p>
 * Instances are produced by {@code LDAIClient.agentConfig} and {@code LDAIClient.agentConfigs};
 * application code does not normally construct them directly.
 */
public final class AIAgentConfig extends AIConfig {
  private final String instructions;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, LDTool> tools;
  private final Evaluator evaluator;

  private AIAgentConfig(Builder builder) {
    super(builder.key, builder.enabled, builder.model, builder.provider, builder.trackerFactory);
    this.instructions = builder.instructions;
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools == null ? null : Collections.unmodifiableMap(builder.tools);
    this.evaluator = builder.evaluator == null ? Evaluator.noop() : builder.evaluator;
  }

  /**
   * Returns the agent instructions, already interpolated.
   *
   * @return the instructions, or {@code null} if none were provided
   */
  public String getInstructions() {
    return instructions;
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
   *
   * @return the evaluator (never {@code null})
   */
  public Evaluator getEvaluator() {
    return evaluator;
  }

  /**
   * Creates a builder for an {@link AIAgentConfig}.
   *
   * @param key the configuration key
   * @return a new builder
   */
  public static Builder builder(String key) {
    return new Builder(key);
  }

  /**
   * A builder for {@link AIAgentConfig} instances.
   */
  public static final class Builder {
    private final String key;
    private boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private Supplier<AIConfigTracker> trackerFactory;
    private String instructions;
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

    /** @param instructions the interpolated agent instructions @return this builder */
    public Builder instructions(String instructions) {
      this.instructions = instructions;
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
     * Builds the agent config.
     *
     * @return a new {@link AIAgentConfig}
     */
    public AIAgentConfig build() {
      return new AIAgentConfig(this);
    }
  }
}
