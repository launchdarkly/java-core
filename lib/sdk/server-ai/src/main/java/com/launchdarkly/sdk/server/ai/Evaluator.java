package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates evaluation of an AI Config output by running a set of {@link Judge} instances.
 * <p>
 * An {@code Evaluator} is attached to an {@link AICompletionConfig} or {@link AIAgentConfig} and
 * invoked by managed AI types (plan 4). In v1.0, the evaluator returned by the config retrieval
 * methods is always a noop that returns an empty list immediately.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class Evaluator {
  private static final Evaluator NOOP = new Evaluator();

  private final Map<String, Judge> judges;
  private final JudgeConfiguration judgeConfiguration;
  private final LDLogger logger;

  private Evaluator() {
    this.judges = Collections.emptyMap();
    this.judgeConfiguration = null;
    this.logger = null;
  }

  /**
   * Constructs an evaluator with the given judges and configuration.
   *
   * @param judges a map from judge config key to {@link Judge} instance; a {@code null} value is
   *     treated as an empty map
   * @param judgeConfiguration the judge configuration listing which judges to run and their sampling
   *     rates
   * @param logger the logger
   */
  public Evaluator(Map<String, Judge> judges, JudgeConfiguration judgeConfiguration, LDLogger logger) {
    this.judges = judges != null
        ? Collections.unmodifiableMap(new HashMap<>(judges))
        : Collections.emptyMap();
    this.judgeConfiguration = judgeConfiguration;
    this.logger = logger;
  }

  /**
   * Returns the shared noop evaluator, which immediately returns an empty result list without
   * logging any warnings.
   *
   * @return the noop singleton, never {@code null}
   */
  public static Evaluator noop() {
    return NOOP;
  }

  /**
   * Runs all configured judges against the given input/output pair and returns their results.
   * <p>
   * Judges are run sequentially in the order specified by the {@link JudgeConfiguration}.
   * Returns an empty list immediately when no judge configuration is present.
   * Judges referenced in the configuration but absent from the judges map are skipped with a
   * warning; this is not an error.
   * <p>
   * This method does NOT call {@code trackJudgeResult} — that is the caller's responsibility.
   *
   * @param input the message history or prompt that was sent to the model
   * @param output the model's response to evaluate
   * @return a completed future holding the list of judge results; never {@code null}
   */
  public CompletableFuture<List<JudgeResult>> evaluate(String input, String output) {
    if (judgeConfiguration == null) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }

    List<JudgeResult> results = new ArrayList<>();
    for (JudgeConfiguration.Judge entry : judgeConfiguration.getJudges()) {
      Judge judge = judges.get(entry.getKey());
      if (judge == null) {
        if (logger != null) logger.warn("Evaluator: no judge found for key '{}', skipping", entry.getKey());
        continue;
      }
      results.add(judge.evaluate(input, output, entry.getSamplingRate()));
    }
    return CompletableFuture.completedFuture(results);
  }
}
