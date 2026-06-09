package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.ToolConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A caller-supplied default for {@link LDAIClient#agentConfig} (and {@link LDAIClient#agentConfigs}),
 * returned (as an {@link AIAgentConfig}) when the flag is absent or cannot be evaluated.
 * <p>
 * Build instances with {@link #builder()}. Instances are immutable.
 */
public final class AIAgentConfigDefault extends AIConfigDefault {
  private final String instructions;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, ToolConfig> tools;

  private AIAgentConfigDefault(Builder builder) {
    super(builder);
    this.instructions = builder.instructions;
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools == null
        ? null : Collections.unmodifiableMap(new LinkedHashMap<>(builder.tools));
  }

  /**
   * Returns the default agent instructions.
   *
   * @return the instructions, or {@code null} if none were specified
   */
  public String getInstructions() {
    return instructions;
  }

  /**
   * Returns the default judge configuration.
   *
   * @return the judge configuration, or {@code null} if none was specified
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the default root-level tools map.
   *
   * @return an unmodifiable map of tools, or {@code null} if none were specified
   */
  public Map<String, ToolConfig> getTools() {
    return tools;
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
   * @return a disabled {@link AIAgentConfigDefault}
   */
  public static AIAgentConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Builder for {@link AIAgentConfigDefault}.
   */
  public static final class Builder extends AbstractBuilder<Builder> {
    private String instructions;
    private JudgeConfiguration judgeConfiguration;
    private Map<String, ToolConfig> tools;

    private Builder() {
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the default agent instructions.
     *
     * @param instructions the instructions; may be {@code null}
     * @return this builder
     */
    public Builder instructions(String instructions) {
      this.instructions = instructions;
      return this;
    }

    /**
     * Sets the default judge configuration.
     *
     * @param judgeConfiguration the judge configuration; may be {@code null}
     * @return this builder
     */
    public Builder judgeConfiguration(JudgeConfiguration judgeConfiguration) {
      this.judgeConfiguration = judgeConfiguration;
      return this;
    }

    /**
     * Sets the default root-level tools map. The map is copied defensively.
     *
     * @param tools the tools; may be {@code null}
     * @return this builder
     */
    public Builder tools(Map<String, ToolConfig> tools) {
      this.tools = tools;
      return this;
    }

    /**
     * Builds the immutable {@link AIAgentConfigDefault}.
     *
     * @return a new {@link AIAgentConfigDefault}
     */
    public AIAgentConfigDefault build() {
      return new AIAgentConfigDefault(this);
    }
  }
}
