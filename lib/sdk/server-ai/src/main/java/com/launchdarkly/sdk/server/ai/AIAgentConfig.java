package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.AIConfigMode;
import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ToolConfig;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A retrieved agent AI Config. This is the result of {@link LDAIClient#agentConfig} (and each entry
 * returned by {@link LDAIClient#agentConfigs}).
 * <p>
 * The {@link #getInstructions() instructions} have already had their template interpolated with the
 * supplied variables and evaluation context. Instances are immutable.
 */
public final class AIAgentConfig extends AIConfig {
  private final String instructions;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, ToolConfig> tools;

  AIAgentConfig(
      String key,
      boolean enabled,
      ModelConfig model,
      ProviderConfig provider,
      String instructions,
      JudgeConfiguration judgeConfiguration,
      Map<String, ToolConfig> tools,
      Supplier<LDAIConfigTracker> trackerFactory) {
    super(key, enabled, AIConfigMode.AGENT, model, provider, trackerFactory);
    this.instructions = instructions;
    this.judgeConfiguration = judgeConfiguration;
    this.tools = tools == null ? null : Collections.unmodifiableMap(tools);
  }

  /**
   * Returns the interpolated agent instructions.
   *
   * @return the instructions, or {@code null} if none were specified
   */
  public String getInstructions() {
    return instructions;
  }

  /**
   * Returns the judge configuration referencing judges that may evaluate this config.
   *
   * @return the judge configuration, or {@code null} if none was specified
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the root-level tools map keyed by tool name.
   *
   * @return an unmodifiable map of tools, or {@code null} if none were specified
   */
  public Map<String, ToolConfig> getTools() {
    return tools;
  }
}
