package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class EvaluatorTest {
  private static final LDLogger LOGGER = LDLogger.withAdapter(Logs.toConsole(), "test");
  private static final AIMetrics METRICS = AIMetrics.builder().success(true).build();

  // ---- helpers ----------------------------------------------------------------

  /**
   * Builds a real Judge with the given key/metric, backed by a mocked Runner and tracker.
   * The runner returns a parsed response with the given score.
   */
  private Judge judgeWithScore(String key, String metricKey, double score) throws Exception {
    Runner runner = mock(Runner.class);
    LDAIConfigTracker tracker = mock(LDAIConfigTracker.class);
    when(tracker.trackMetricsOf(any(Function.class), any(Callable.class)))
        .thenAnswer(inv -> {
          Callable<?> op = inv.getArgument(1);
          return op.call();
        });

    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", score);
    parsed.put("reasoning", "test reasoning");
    RunnerResult result = RunnerResult.builder("content", METRICS).parsed(parsed).build();
    when(runner.run(any(), any())).thenReturn(result);

    AIJudgeConfig config = new AIJudgeConfig(key, true, null, null, null, metricKey, () -> tracker);
    return new Judge(config, runner, LOGGER);
  }

  // ---- noop -------------------------------------------------------------------

  @Test
  public void noopReturnsEmptyList() throws Exception {
    List<JudgeResult> results = Evaluator.noop().evaluate("input", "output").get();
    assertThat(results, empty());
  }

  @Test
  public void noopReturnsSameInstance() {
    assertThat(Evaluator.noop(), is(Evaluator.noop()));
  }

  @Test
  public void noopFutureIsAlreadyDone() {
    assertThat(Evaluator.noop().evaluate("input", "output").isDone(), is(true));
  }

  // ---- single judge -----------------------------------------------------------

  @Test
  public void singleJudgeIsRun() throws Exception {
    Judge judge = judgeWithScore("j1", "metric.1", 0.9);
    Map<String, Judge> judges = Collections.singletonMap("j1", judge);
    JudgeConfiguration config = new JudgeConfiguration(
        Collections.singletonList(new JudgeConfiguration.Judge("j1", 1.0)));

    Evaluator evaluator = new Evaluator(judges, config, LOGGER);
    List<JudgeResult> results = evaluator.evaluate("input", "output").get();

    assertThat(results, hasSize(1));
    assertThat(results.get(0).isSuccess(), is(true));
    assertThat(results.get(0).getScore(), is(0.9));
  }

  // ---- multiple judges run sequentially ---------------------------------------

  @Test
  public void multipleJudgesAreAllRun() throws Exception {
    Judge j1 = judgeWithScore("j1", "m1", 0.8);
    Judge j2 = judgeWithScore("j2", "m2", 0.6);
    Map<String, Judge> judgesMap = new HashMap<>();
    judgesMap.put("j1", j1);
    judgesMap.put("j2", j2);
    JudgeConfiguration config = new JudgeConfiguration(Arrays.asList(
        new JudgeConfiguration.Judge("j1", 1.0),
        new JudgeConfiguration.Judge("j2", 1.0)));

    Evaluator evaluator = new Evaluator(judgesMap, config, LOGGER);
    List<JudgeResult> results = evaluator.evaluate("input", "output").get();

    assertThat(results, hasSize(2));
    assertThat(results.get(0).getScore(), is(0.8));
    assertThat(results.get(1).getScore(), is(0.6));
  }

  // ---- missing judge is skipped with a warning --------------------------------

  @Test
  public void missingJudgeIsSkipped() throws Exception {
    Judge j1 = judgeWithScore("j1", "m1", 0.7);
    Map<String, Judge> judgesMap = Collections.singletonMap("j1", j1);
    JudgeConfiguration config = new JudgeConfiguration(Arrays.asList(
        new JudgeConfiguration.Judge("j1", 1.0),
        new JudgeConfiguration.Judge("missing-judge", 1.0)));

    Evaluator evaluator = new Evaluator(judgesMap, config, LOGGER);
    List<JudgeResult> results = evaluator.evaluate("input", "output").get();

    assertThat(results, hasSize(1));
    assertThat(results.get(0).getJudgeConfigKey(), is("j1"));
  }

  // ---- evaluator does NOT call trackJudgeResult --------------------------------

  @Test
  public void evaluatorDoesNotCallTrackJudgeResult() throws Exception {
    LDAIConfigTracker outerTracker = mock(LDAIConfigTracker.class);

    Runner runner = mock(Runner.class);
    LDAIConfigTracker innerTracker = mock(LDAIConfigTracker.class);
    when(innerTracker.trackMetricsOf(any(Function.class), any(Callable.class)))
        .thenAnswer(inv -> {
          Callable<?> op = inv.getArgument(1);
          return op.call();
        });
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.5);
    when(runner.run(any(), any()))
        .thenReturn(RunnerResult.builder("content", METRICS).parsed(parsed).build());

    AIJudgeConfig judgeConfig = new AIJudgeConfig("jk", true, null, null, null, "mk", () -> innerTracker);
    Judge judge = new Judge(judgeConfig, runner, LOGGER);

    Map<String, Judge> judgesMap = Collections.singletonMap("jk", judge);
    JudgeConfiguration config = new JudgeConfiguration(
        Collections.singletonList(new JudgeConfiguration.Judge("jk", 1.0)));

    Evaluator evaluator = new Evaluator(judgesMap, config, LOGGER);
    evaluator.evaluate("input", "output").get();

    verify(outerTracker, never()).trackJudgeResult(any());
  }

  // ---- returned future is already complete ------------------------------------

  @Test
  public void returnedFutureIsAlreadyDone() throws Exception {
    Judge judge = judgeWithScore("j1", "m1", 0.5);
    Map<String, Judge> judgesMap = Collections.singletonMap("j1", judge);
    JudgeConfiguration config = new JudgeConfiguration(
        Collections.singletonList(new JudgeConfiguration.Judge("j1", 1.0)));

    Evaluator evaluator = new Evaluator(judgesMap, config, LOGGER);
    assertThat(evaluator.evaluate("input", "output").isDone(), is(true));
  }
}
