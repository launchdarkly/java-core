package com.launchdarkly.sdk.server.ai;

/**
 * Supplies a {@link Runner} for a given judge AI Config.
 * <p>
 * Implement this interface and pass it to
 * {@link LDAIClientImpl#LDAIClientImpl(com.launchdarkly.sdk.server.interfaces.LDClientInterface,
 * com.launchdarkly.logging.LDLogger, AIRunnerProvider)} to enable online evaluation (judges).
 * The client calls {@link #create} once per judge key when building an {@link Evaluator} for a
 * completion or agent config that carries a {@code judgeConfiguration}.
 * <p>
 * Return {@code null} to skip a particular judge. Implementations should be thread-safe.
 */
@FunctionalInterface
public interface AIRunnerProvider {
  /**
   * Creates a {@link Runner} for the given judge AI Config.
   *
   * @param judgeConfig the judge AI Config for which a runner is needed; never {@code null}
   * @return a runner to use for this judge, or {@code null} to skip this judge
   */
  Runner create(AIJudgeConfig judgeConfig);
}
