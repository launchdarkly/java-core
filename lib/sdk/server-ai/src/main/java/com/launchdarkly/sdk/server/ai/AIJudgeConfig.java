package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Provider;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A retrieved judge AI Config. This is the result of {@link LDAIClient#judgeConfig}.
 * <p>
 * A judge config evaluates the output of another configuration; it carries the
 * {@link #getEvaluationMetricKey() evaluation metric key} it reports against. The
 * {@link #getMessages() messages} have already had their templates interpolated. Instances are
 * immutable.
 */
public final class AIJudgeConfig extends AIConfig {
  private final List<Message> messages;
  private final String evaluationMetricKey;

  AIJudgeConfig(
      String key,
      boolean enabled,
      Model model,
      Provider provider,
      List<Message> messages,
      String evaluationMetricKey,
      Supplier<LDAIConfigTracker> trackerFactory) {
    super(key, enabled, Mode.JUDGE, model, provider, trackerFactory);
    this.messages = messages == null ? null : Collections.unmodifiableList(messages);
    this.evaluationMetricKey = evaluationMetricKey;
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
   * Returns the metric key this judge reports against.
   *
   * @return the evaluation metric key, or {@code null} if none was resolved
   */
  public String getEvaluationMetricKey() {
    return evaluationMetricKey;
  }
}
