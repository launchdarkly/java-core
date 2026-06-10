package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Provider;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Tool;

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
  private final List<Message> messages;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, Tool> tools;

  AICompletionConfig(
      String key,
      boolean enabled,
      Model model,
      Provider provider,
      List<Message> messages,
      JudgeConfiguration judgeConfiguration,
      Map<String, Tool> tools,
      Supplier<LDAIConfigTracker> trackerFactory) {
    super(key, enabled, Mode.COMPLETION, model, provider, trackerFactory);
    this.messages = messages == null ? null : Collections.unmodifiableList(messages);
    this.judgeConfiguration = judgeConfiguration;
    this.tools = tools == null ? null : Collections.unmodifiableMap(tools);
  }

  /**
   * Returns the interpolated prompt messages.
   *
   * @return an unmodifiable list of messages, or {@code null} if none were specified
   */
  public List<Message> getMessages() {
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
  public Map<String, Tool> getTools() {
    return tools;
  }
}
