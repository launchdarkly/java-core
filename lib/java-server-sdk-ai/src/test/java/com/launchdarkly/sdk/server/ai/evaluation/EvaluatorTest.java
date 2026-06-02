package com.launchdarkly.sdk.server.ai.evaluation;

import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EvaluatorTest {
  /** A judge that records the order in which it was invoked and returns a fixed result. */
  private static final class FakeJudge implements Judge {
    private final JudgeResult result;
    private final List<String> invocationLog;
    private final String name;

    FakeJudge(String name, JudgeResult result, List<String> invocationLog) {
      this.name = name;
      this.result = result;
      this.invocationLog = invocationLog;
    }

    @Override
    public CompletableFuture<JudgeResult> evaluate(String input, String output) {
      invocationLog.add(name);
      return CompletableFuture.completedFuture(result);
    }

    @Override
    public AIJudgeConfig getConfig() {
      return null;
    }
  }

  @Test
  public void noopEvaluatorResolvesToEmptyList() throws Exception {
    List<JudgeResult> results = Evaluator.noop().evaluate("in", "out").get();
    assertTrue(results.isEmpty());
  }

  @Test
  public void runsEachJudgeInOrder() throws ExecutionException, InterruptedException {
    List<String> log = new ArrayList<>();
    JudgeResult a = JudgeResult.builder().sampled(true).success(true).metricKey("a").score(0.1).build();
    JudgeResult b = JudgeResult.builder().sampled(true).success(true).metricKey("b").score(0.2).build();
    Evaluator evaluator = new Evaluator(Arrays.asList(
        new FakeJudge("first", a, log),
        new FakeJudge("second", b, log)));

    List<JudgeResult> results = evaluator.evaluate("in", "out").get();

    assertEquals(Arrays.asList("first", "second"), log);
    assertEquals(2, results.size());
    assertEquals("a", results.get(0).getMetricKey());
    assertEquals("b", results.get(1).getMetricKey());
  }
}
