package com.launchdarkly.sdk.server.ai.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates multiple judge evaluations for a single AI Config invocation.
 * <p>
 * Instances are created by the SDK and attached to {@code AICompletionConfig} and
 * {@code AIAgentConfig} results, so callers can run all configured judges with a single call.
 * Configurations without judges carry a {@link #noop() no-op} evaluator.
 * <p>
 * The evaluator coordinates evaluations only; it does not perform any LaunchDarkly event tracking.
 * Tracking of {@link JudgeResult} values is the responsibility of the caller (typically via
 * {@code AIConfigTracker.trackJudgeResult}).
 */
public final class Evaluator {
  private final List<Judge> judges;

  /**
   * Creates an evaluator wrapping the given judges. Each judge applies its own sampling rate.
   *
   * @param judges the initialized judges
   */
  public Evaluator(List<Judge> judges) {
    this.judges = Collections.unmodifiableList(new ArrayList<>(judges));
  }

  /**
   * Returns a no-op evaluator that resolves immediately to an empty result and invokes no judges.
   *
   * @return a no-op evaluator
   */
  public static Evaluator noop() {
    return new Evaluator(Collections.emptyList());
  }

  /**
   * Runs all configured judges against the input/output pair.
   * <p>
   * Judges are evaluated in order; the returned future resolves once every judge has completed.
   *
   * @param input the input that was provided to the AI model
   * @param output the AI-generated output to evaluate
   * @return a future that resolves to one {@link JudgeResult} per configured judge, in order
   */
  public CompletableFuture<List<JudgeResult>> evaluate(String input, String output) {
    if (judges.isEmpty()) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
    CompletableFuture<List<JudgeResult>> chain = CompletableFuture.completedFuture(new ArrayList<>());
    for (Judge judge : judges) {
      chain = chain.thenCompose(accumulated ->
          judge.evaluate(input, output).thenApply(result -> {
            accumulated.add(result);
            return accumulated;
          }));
    }
    return chain.thenApply(Collections::unmodifiableList);
  }
}
