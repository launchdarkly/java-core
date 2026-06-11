package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a fixed set of {@link Judge}s against one input/output pair and collects their results.
 * <p>
 * Each judge runs with <strong>fault isolation</strong>: a judge that throws or times out yields a
 * failed {@link JudgeResult} for that judge while every other judge's result is preserved, in the
 * original order. Judges run concurrently and each is bounded by a per-judge timeout so a single
 * hung judge cannot stall the whole evaluation.
 * <p>
 * The evaluator does not record results; recording the returned {@link JudgeResult}s (for example
 * via a tracker) is the caller's responsibility. Instances are immutable and thread-safe.
 * <p>
 * This type is not part of the public API in v1.0 and may change without notice.
 */
final class Evaluator {
  /**
   * Default per-judge timeout used when one is not supplied.
   */
  public static final Duration DEFAULT_PER_JUDGE_TIMEOUT = Duration.ofSeconds(30);

  private final List<Judge> judges;
  private final Duration perJudgeTimeout;
  private final LDLogger logger;

  /**
   * Creates an evaluator using the {@link #DEFAULT_PER_JUDGE_TIMEOUT default per-judge timeout}.
   *
   * @param judges the judges to run; must not be {@code null}
   * @param logger the logger; must not be {@code null}
   */
  public Evaluator(List<Judge> judges, LDLogger logger) {
    this(judges, DEFAULT_PER_JUDGE_TIMEOUT, Objects.requireNonNull(logger, "logger"));
  }

  /**
   * Creates an evaluator with an explicit per-judge timeout.
   *
   * @param judges the judges to run; must not be {@code null}
   * @param perJudgeTimeout the maximum time to wait for each judge; must not be {@code null}
   * @param logger the logger; must not be {@code null}
   */
  public Evaluator(List<Judge> judges, Duration perJudgeTimeout, LDLogger logger) {
    this.judges = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(judges, "judges")));
    this.perJudgeTimeout = Objects.requireNonNull(perJudgeTimeout, "perJudgeTimeout");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * Returns an evaluator with no judges. Its {@link #evaluate} returns an empty list and logs
   * nothing.
   *
   * @return a no-op evaluator
   */
  public static Evaluator noop() {
    return new Evaluator(
        Collections.emptyList(), DEFAULT_PER_JUDGE_TIMEOUT, LDLogger.withAdapter(Logs.none(), ""));
  }

  /**
   * Runs every judge against the given input and output.
   *
   * @param input the input that was provided to the AI being evaluated
   * @param output the AI-generated response to score
   * @return one {@link JudgeResult} per judge, in the judges' order; empty when there are no judges
   */
  public List<JudgeResult> evaluate(String input, String output) {
    if (judges.isEmpty()) {
      return Collections.emptyList();
    }

    ExecutorService pool = Executors.newFixedThreadPool(judges.size());
    try {
      List<Future<JudgeResult>> futures = new ArrayList<>(judges.size());
      for (Judge judge : judges) {
        futures.add(pool.submit(() -> judge.evaluate(input, output)));
      }

      List<JudgeResult> results = new ArrayList<>(judges.size());
      for (int i = 0; i < judges.size(); i++) {
        results.add(awaitResult(judges.get(i), futures.get(i)));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private JudgeResult awaitResult(Judge judge, Future<JudgeResult> future) {
    String key = judge.getAIConfig().getKey();
    try {
      return future.get(perJudgeTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      logger.warn("Judge {} timed out after {} ms", key, perJudgeTimeout.toMillis());
      return failed(key, "Judge evaluation timed out");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      logger.error("Judge {} failed: {}", key, cause.toString());
      return failed(key, cause.getMessage() != null ? cause.getMessage() : "Unknown error");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      return failed(key, "Judge evaluation interrupted");
    }
  }

  private static JudgeResult failed(String key, String message) {
    return JudgeResult.builder(true, false).judgeConfigKey(key).errorMessage(message).build();
  }
}
