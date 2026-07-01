package com.launchdarkly.sdk.server.ai;

import java.util.Map;

/**
 * Executes an AI operation and returns a {@link RunnerResult}.
 * <p>
 * Implement this interface to wrap a model provider SDK so it can be used by a {@link Judge} or
 * managed AI type. The SDK passes an optional {@code outputType} schema when it needs structured
 * output (for example, when a judge requests a {@code {score, reasoning}} object).
 * <p>
 * Implementations should be thread-safe; a single instance may be called from multiple threads.
 */
public interface Runner {
  /**
   * Runs the AI operation with the given input and optional output schema.
   *
   * @param input the prompt or message history to send to the model; never {@code null}
   * @param outputType a JSON-Schema-like map describing the expected structured output, or
   *     {@code null} if no structured output is required
   * @return the result of the operation; never {@code null}
   * @throws Exception if the underlying provider call fails
   */
  RunnerResult run(String input, Map<String, Object> outputType) throws Exception;

  /**
   * Runs the AI operation with the given input and no output-type constraint.
   * <p>
   * Delegates to {@link #run(String, Map)} with a {@code null} {@code outputType}.
   *
   * @param input the prompt or message history to send to the model; never {@code null}
   * @return the result of the operation; never {@code null}
   * @throws Exception if the underlying provider call fails
   */
  default RunnerResult run(String input) throws Exception {
    return run(input, null);
  }
}
