package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;
import com.launchdarkly.sdk.server.ai.internal.LDAIConfigTrackerImpl;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class EvaluatorTest {
  private LDClientInterface client;
  private LDLogger logger;
  private final LDContext context = LDContext.create("user-key");

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logger = LDLogger.withAdapter(Logs.capture(), "test");
  }

  private Judge judge(String key, Runner runner) {
    Supplier<LDAIConfigTracker> trackerFactory = () -> new LDAIConfigTrackerImpl(
        client, "run-" + key, key, "v1", 1, "gpt-4", "openai", context, null, logger);
    AIJudgeConfig config = new AIJudgeConfig(key, true, null, null, null, "relevance", trackerFactory);
    return new Judge(config, runner, 1.0, logger);
  }

  private static Runner scoring(double score) {
    return input -> RunnerResult.builder(Metrics.builder(true).build())
        .parsed(LDValue.buildObject().put("score", score).put("reasoning", "r").build())
        .build();
  }

  @Test
  public void noopReturnsEmptyListAndLogsNothing() {
    List<JudgeResult> results = Evaluator.noop().evaluate("q", "a");
    assertThat(results, is(empty()));
  }

  @Test
  public void runsEveryJudgePreservingOrder() {
    Evaluator evaluator = new Evaluator(
        Arrays.asList(judge("first", scoring(0.1)), judge("second", scoring(0.2))), logger);
    List<JudgeResult> results = evaluator.evaluate("q", "a");
    assertThat(results, hasSize(2));
    assertThat(results.get(0).getJudgeConfigKey(), is("first"));
    assertThat(results.get(0).getScore(), is(0.1));
    assertThat(results.get(1).getJudgeConfigKey(), is("second"));
    assertThat(results.get(1).getScore(), is(0.2));
  }

  @Test
  public void faultyJudgeIsolatedAndOthersPreserved() {
    Runner failing = input -> {
      throw new RuntimeException("boom");
    };
    Evaluator evaluator = new Evaluator(
        Arrays.asList(judge("ok", scoring(0.9)), judge("bad", failing)), logger);
    List<JudgeResult> results = evaluator.evaluate("q", "a");
    assertThat(results, hasSize(2));
    assertThat(results.get(0).isSuccess(), is(true));
    assertThat(results.get(0).getScore(), is(0.9));
    assertThat(results.get(1).isSuccess(), is(false));
    assertThat(results.get(1).getErrorMessage(), is("boom"));
  }

  @Test
  public void hungJudgeTimesOutWithoutStallingChain() {
    Runner slow = input -> {
      Thread.sleep(5000);
      return RunnerResult.builder(Metrics.builder(true).build()).build();
    };
    Evaluator evaluator = new Evaluator(
        Arrays.asList(judge("fast", scoring(0.7)), judge("slow", slow)),
        Duration.ofMillis(150),
        logger);
    List<JudgeResult> results = evaluator.evaluate("q", "a");
    assertThat(results, hasSize(2));
    assertThat(results.get(0).isSuccess(), is(true));
    assertThat(results.get(1).isSuccess(), is(false));
    assertThat(results.get(1).getErrorMessage(), containsString("timed out"));
    assertThat(results.get(1).getJudgeConfigKey(), is("slow"));
  }

  @Test
  public void resultsAreInJudgeOrderEvenWhenCompletionOrderDiffers() {
    Runner slowOk = input -> {
      Thread.sleep(300);
      return RunnerResult.builder(Metrics.builder(true).build())
          .parsed(LDValue.buildObject().put("score", 0.5).put("reasoning", "r").build())
          .build();
    };
    Evaluator evaluator = new Evaluator(
        Arrays.asList(judge("slow", slowOk), judge("fast", scoring(0.6))),
        Duration.ofSeconds(5),
        logger);
    List<JudgeResult> results = evaluator.evaluate("q", "a");
    assertThat(
        Arrays.asList(results.get(0).getJudgeConfigKey(), results.get(1).getJudgeConfigKey()),
        contains("slow", "fast"));
  }
}
