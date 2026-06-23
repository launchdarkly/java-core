package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message.Role;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class JudgeTest {
  private Runner runner;
  private LDAIConfigTracker tracker;
  private AIJudgeConfig judgeConfig;
  private Judge judge;
  private static final LDLogger LOGGER = LDLogger.withAdapter(Logs.toConsole(), "test");
  private static final AIMetrics METRICS = AIMetrics.builder().success(true).build();

  @Before
  public void setUp() throws Exception {
    runner = mock(Runner.class);
    tracker = mock(LDAIConfigTracker.class);
    // By default trackMetricsOf delegates to the callable
    when(tracker.trackMetricsOf(any(Function.class), any(Callable.class)))
        .thenAnswer(inv -> {
          Callable<?> op = inv.getArgument(1);
          return op.call();
        });
    judgeConfig = makeJudgeConfig("judge-key", "my.metric", tracker);
    judge = new Judge(judgeConfig, runner, LOGGER);
  }

  private AIJudgeConfig makeJudgeConfig(String key, String metricKey, LDAIConfigTracker tracker) {
    return new AIJudgeConfig(key, true, null, null, null, metricKey, () -> tracker);
  }

  private RunnerResult resultWithParsed(Map<String, Object> parsed) {
    return RunnerResult.builder("output", METRICS).parsed(parsed).build();
  }

  // ---- successful evaluation --------------------------------------------------

  @Test
  public void successfulEvaluationReturnsCorrectScore() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.85);
    parsed.put("reasoning", "Looks good");
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");

    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(true));
    assertThat(result.getScore(), is(0.85));
    assertThat(result.getReasoning(), is("Looks good"));
    assertThat(result.getJudgeConfigKey(), is("judge-key"));
    assertThat(result.getMetricKey(), is("my.metric"));
  }

  @Test
  public void scoreBoundaryZeroIsValid() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.0);
    parsed.put("reasoning", "Terrible");
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(true));
    assertThat(result.getScore(), is(0.0));
  }

  @Test
  public void scoreBoundaryOneIsValid() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 1.0);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(true));
    assertThat(result.getScore(), is(1.0));
  }

  @Test
  public void reasoningIsOptional() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.5);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(true));
    assertThat(result.getReasoning(), nullValue());
  }

  // ---- error paths ------------------------------------------------------------

  @Test
  public void runnerExceptionResultsInFailure() throws Exception {
    when(runner.run(any(), any())).thenThrow(new RuntimeException("boom"));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getErrorMessage(), is("boom"));
  }

  @Test
  public void nullParsedResultsInFailure() throws Exception {
    when(runner.run(any(), any())).thenReturn(RunnerResult.builder("content", METRICS).build());

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(false));
  }

  @Test
  public void missingScoreResultsInFailure() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("reasoning", "No score here");
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(false));
  }

  @Test
  public void scoreAboveOneResultsInFailure() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 1.5);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(false));
  }

  @Test
  public void scoreBelowZeroResultsInFailure() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", -0.1);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output");
    assertThat(result.isSuccess(), is(false));
  }

  // ---- sampling ---------------------------------------------------------------

  @Test
  public void zeroSamplingRateAlwaysSkips() throws Exception {
    JudgeResult result = judge.evaluate("input", "output", 0.0);

    assertThat(result.isSampled(), is(false));
    assertThat(result.isSuccess(), is(false));
    verify(runner, never()).run(any(), any());
  }

  @Test
  public void fullSamplingRateAlwaysRuns() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.5);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluate("input", "output", 1.0);
    assertThat(result.isSampled(), is(true));
    verify(runner).run(any(), any());
  }

  // ---- evaluateMessages -------------------------------------------------------

  @Test
  public void evaluateMessagesFormatsCorrectly() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.9);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    List<Message> messages = Arrays.asList(
        new Message(Role.USER, "Hello"),
        new Message(Role.ASSISTANT, "Hi there"));
    RunnerResult response = RunnerResult.builder("Hi there", METRICS).build();
    JudgeResult result = judge.evaluateMessages(messages, response);

    assertThat(result.isSuccess(), is(true));
    verify(runner).run(any(), any());
  }

  @Test
  public void evaluateMessagesWithNullMessagesDoesNotThrow() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.5);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    JudgeResult result = judge.evaluateMessages(null, RunnerResult.builder("content", METRICS).build());
    assertThat(result, notNullValue());
  }

  // ---- tracker delegation -----------------------------------------------------

  @Test
  public void trackerMetricsOfIsInvoked() throws Exception {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.7);
    when(runner.run(any(), any())).thenReturn(resultWithParsed(parsed));

    judge.evaluate("input", "output");

    verify(tracker).trackMetricsOf(any(Function.class), any(Callable.class));
  }

  // ---- accessors --------------------------------------------------------------

  @Test
  public void getConfigReturnsConfig() {
    assertThat(judge.getConfig(), is(judgeConfig));
  }

  @Test
  public void getRunnerReturnsRunner() {
    assertThat(judge.getRunner(), is(runner));
  }
}
