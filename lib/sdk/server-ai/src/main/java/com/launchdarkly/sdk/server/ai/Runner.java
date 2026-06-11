package com.launchdarkly.sdk.server.ai;

/**
 * Invokes an AI model with a string input and returns its result.
 * <p>
 * In v1.0 the AI SDK does not ship provider-specific runners; an application supplies its own
 * {@code Runner} (for example wrapping an OpenAI or Bedrock call) when creating a {@link Judge} via
 * {@link LDAIClient#createJudge}. Built-in provider runners are planned for a later release.
 * <p>
 * For structured-output use cases such as judge evaluation, the runner is expected to make the
 * model's parsed JSON available via {@link RunnerResult#getParsed()}.
 * <p>
 * Implementations should be safe to invoke from multiple threads if the same runner is shared across
 * concurrently-evaluating judges.
 */
public interface Runner {
  /**
   * Invokes the model with the given input.
   *
   * @param input the input string to send to the model
   * @return the model result; must not be {@code null}
   * @throws Exception if the invocation fails; the caller (a {@link Judge}) records the failure and
   *     surfaces it as a failed evaluation rather than propagating it
   */
  RunnerResult run(String input) throws Exception;
}
