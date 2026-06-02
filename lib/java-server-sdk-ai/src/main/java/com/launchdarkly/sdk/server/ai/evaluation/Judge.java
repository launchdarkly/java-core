package com.launchdarkly.sdk.server.ai.evaluation;

import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfig;

import java.util.concurrent.CompletableFuture;

/**
 * Evaluates AI outputs against a configured metric.
 * <p>
 * A {@code Judge} pairs an {@link AIJudgeConfig} with a provider-specific model runner that performs
 * the actual evaluation. Provider-backed implementations are supplied by AI provider integration
 * packages; this interface is the seam through which they plug into the SDK's {@link Evaluator}.
 */
public interface Judge {
  /**
   * Evaluates the given input/output pair.
   * <p>
   * The judge applies its own sampling rate: when an evaluation is skipped by sampling, the returned
   * result has {@link JudgeResult#isSampled()} set to {@code false}.
   *
   * @param input the input that was provided to the AI model
   * @param output the AI-generated output to evaluate
   * @return a future that resolves to the evaluation result
   */
  CompletableFuture<JudgeResult> evaluate(String input, String output);

  /**
   * Returns the judge configuration retrieved during initialization.
   *
   * @return the judge configuration
   */
  AIJudgeConfig getConfig();
}
