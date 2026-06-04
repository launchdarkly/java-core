package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.Map;

/**
 * A user-constructed default value for {@code LDAIClient.agentConfig} and
 * {@code LDAIClient.agentConfigs}.
 */
public final class AIAgentConfigDefault extends AIConfigDefault {
  private final String instructions;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, LDTool> tools;

  private AIAgentConfigDefault(Builder builder) {
    super(builder.enabled, builder.model, builder.provider);
    this.instructions = builder.instructions;
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools;
  }

  /**
   * Returns a disabled default.
   *
   * @return a default with {@code enabled} set to {@code false}
   */
  public static AIAgentConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Returns the default agent instructions.
   *
   * @return the instructions, or {@code null} if none were provided
   */
  public String getInstructions() {
    return instructions;
  }

  /**
   * Returns the default judge configuration.
   *
   * @return the judge configuration, or {@code null} if none was provided
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the default tools map.
   *
   * @return the tools, or {@code null} if none were provided
   */
  public Map<String, LDTool> getTools() {
    return tools;
  }

  @Override
  public LDValue toLDValue() {
    ObjectBuilder builder = baseObject();
    if (instructions != null) {
      builder.put("instructions", instructions);
    }
    if (judgeConfiguration != null) {
      builder.put("judgeConfiguration", judgeConfiguration.toLDValue());
    }
    if (tools != null) {
      builder.put("tools", AICompletionConfigDefault.toolsToLDValue(tools));
    }
    return builder.build();
  }

  /**
   * Creates a builder for an {@link AIAgentConfigDefault}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link AIAgentConfigDefault} instances.
   */
  public static final class Builder {
    private Boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private String instructions;
    private JudgeConfiguration judgeConfiguration;
    private Map<String, LDTool> tools;

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

    /** @param instructions the default agent instructions @return this builder */
    public Builder instructions(String instructions) {
      this.instructions = instructions;
      return this;
    }

    /** @param judgeConfiguration the default judge configuration @return this builder */
    public Builder judgeConfiguration(JudgeConfiguration judgeConfiguration) {
      this.judgeConfiguration = judgeConfiguration;
      return this;
    }

    /** @param tools the default tools map @return this builder */
    public Builder tools(Map<String, LDTool> tools) {
      this.tools = tools;
      return this;
    }

    /**
     * Builds the default.
     *
     * @return a new {@link AIAgentConfigDefault}
     */
    public AIAgentConfigDefault build() {
      return new AIAgentConfigDefault(this);
    }
  }
}
