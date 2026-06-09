package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.AIConfigMode;
import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ToolConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A retrieved completion (chat/prompt) AI Config. This is the result of
 * {@link LDAIClient#completionConfig}.
 * <p>
 * The {@link #getMessages() messages} have already had their templates interpolated with the
 * supplied variables and evaluation context. Instances are immutable.
 */
public final class AICompletionConfig extends AIConfig {
  private final List<LDMessage> messages;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, ToolConfig> tools;

  AICompletionConfig(
      String key,
      boolean enabled,
      ModelConfig model,
      ProviderConfig provider,
      List<LDMessage> messages,
      JudgeConfiguration judgeConfiguration,
      Map<String, ToolConfig> tools,
      Supplier<LDAIConfigTracker> trackerFactory) {
    super(key, enabled, AIConfigMode.COMPLETION, model, provider, trackerFactory);
    this.messages = messages == null ? null : Collections.unmodifiableList(messages);
    this.judgeConfiguration = judgeConfiguration;
    this.tools = tools == null ? null : Collections.unmodifiableMap(tools);
  }

  /**
   * Returns the interpolated prompt messages.
   *
   * @return an unmodifiable list of messages, or {@code null} if none were specified
   */
  public List<LDMessage> getMessages() {
    return messages;
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
