package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;
import com.launchdarkly.sdk.server.ai.internal.LDAIConfigTrackerImpl;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class JudgeTest {
  private LDClientInterface client;
  private LDLogger logger;
  private final LDContext context = LDContext.create("user-key");

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logger = LDLogger.withAdapter(Logs.capture(), "test");
  }

  private AIJudgeConfig judgeConfig(String metricKey, boolean enabled) {
    Supplier<LDAIConfigTracker> trackerFactory = () -> new LDAIConfigTrackerImpl(
        client, "run-1", "judge-key", "v1", 1, "gpt-4", "openai", context, null, logger);
    return new AIJudgeConfig("judge-key", enabled, null, null, null, metricKey, trackerFactory);
  }

  private static Runner runnerReturning(double score, String reasoning) {
    return input -> RunnerResult.builder(Metrics.builder(true).build())
        .content("evaluated")
        .parsed(LDValue.buildObject().put("score", score).put("reasoning", reasoning).build())
        .build();
  }

  @Test
  public void evaluateScoresResponseAndReportsMetricKey() {
    Judge judge = new Judge(judgeConfig("relevance", true), runnerReturning(0.8, "well grounded"), 1.0, logger);
    JudgeResult result = judge.evaluate("the question", "the answer");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(true));
    assertThat(result.getScore(), is(0.8));
    assertThat(result.getReasoning(), is("well grounded"));
    assertThat(result.getMetricKey(), is("relevance"));
    assertThat(result.getJudgeConfigKey(), is("judge-key"));
  }

  @Test
  public void evaluateBuildsWellKnownInputFormat() {
    AtomicReference<String> captured = new AtomicReference<>();
    Runner capturing = input -> {
      captured.set(input);
      return RunnerResult.builder(Metrics.builder(true).build())
          .parsed(LDValue.buildObject().put("score", 0.5).put("reasoning", "ok").build())
          .build();
    };
    Judge judge = new Judge(judgeConfig("relevance", true), capturing, 1.0, logger);
    judge.evaluate("what is 2+2?", "4");
    assertThat(captured.get(), is("MESSAGE HISTORY:\nwhat is 2+2?\n\nRESPONSE TO EVALUATE:\n4"));
  }

  @Test
  public void zeroSamplingRateSkipsInvocation() {
    AtomicReference<Boolean> invoked = new AtomicReference<>(false);
    Runner runner = input -> {
      invoked.set(true);
      return RunnerResult.builder(Metrics.builder(true).build()).build();
    };
    Judge judge = new Judge(judgeConfig("relevance", true), runner, 0.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    assertThat(result.isSampled(), is(false));
    assertThat(result.isSuccess(), is(false));
    assertThat(invoked.get(), is(false));
  }

  @Test
  public void missingEvaluationMetricKeyYieldsFailure() {
    Judge judge = new Judge(judgeConfig("   ", true), runnerReturning(0.8, "x"), 1.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getErrorMessage(), containsString("evaluation metric key"));
  }

  @Test
  public void outOfRangeScoreFailsToParse() {
    Judge judge = new Judge(judgeConfig("relevance", true), runnerReturning(1.5, "too high"), 1.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getScore(), is(nullValue()));
  }

  @Test
  public void missingReasoningFailsToParse() {
    Runner runner = input -> RunnerResult.builder(Metrics.builder(true).build())
        .parsed(LDValue.buildObject().put("score", 0.5).build())
        .build();
    Judge judge = new Judge(judgeConfig("relevance", true), runner, 1.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getScore(), is(nullValue()));
  }

  @Test
  public void runnerFailureYieldsFailedResult() {
    Runner runner = input -> {
      throw new RuntimeException("model exploded");
    };
    Judge judge = new Judge(judgeConfig("relevance", true), runner, 1.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    assertThat(result.isSampled(), is(true));
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getErrorMessage(), is("model exploded"));
  }

  @Test
  public void runnerReportingFailureMetricsYieldsUnsuccessfulResult() {
    Runner runner = input -> RunnerResult.builder(Metrics.builder(false).build())
        .parsed(LDValue.buildObject().put("score", 0.3).put("reasoning", "weak").build())
        .build();
    Judge judge = new Judge(judgeConfig("relevance", true), runner, 1.0, logger);
    JudgeResult result = judge.evaluate("q", "a");
    // Parsed successfully, but the runner's own metrics say the call did not succeed.
    assertThat(result.isSuccess(), is(false));
    assertThat(result.getScore(), is(0.3));
  }

  @Test
  public void evaluateMessagesRendersRolePrefixedHistory() {
    AtomicReference<String> captured = new AtomicReference<>();
    Runner capturing = input -> {
      captured.set(input);
      return RunnerResult.builder(Metrics.builder(true).build())
          .parsed(LDValue.buildObject().put("score", 0.9).put("reasoning", "great").build())
          .build();
    };
    Judge judge = new Judge(judgeConfig("relevance", true), capturing, 1.0, logger);
    RunnerResult response = RunnerResult.builder(Metrics.builder(true).build()).content("the answer").build();
    judge.evaluateMessages(
        Arrays.asList(new Message(Message.Role.SYSTEM, "be helpful"), new Message(Message.Role.USER, "hi")),
        response);
    assertThat(captured.get(),
        is("MESSAGE HISTORY:\nsystem: be helpful\nuser: hi\n\nRESPONSE TO EVALUATE:\nthe answer"));
  }

  @Test
  public void normalizeSampleRateClampsAndDefaults() {
    assertThat(Judge.normalizeSampleRate(-0.5), is(0.0));
    assertThat(Judge.normalizeSampleRate(2.0), is(1.0));
    assertThat(Judge.normalizeSampleRate(Double.NaN), is(1.0));
    assertThat(Judge.normalizeSampleRate(Double.POSITIVE_INFINITY), is(1.0));
    assertThat(Judge.normalizeSampleRate(0.42), is(0.42));
  }
}
