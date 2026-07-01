package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Evaluates an AI model output against a judge prompt, returning a scored {@link JudgeResult}.
 * <p>
 * A {@code Judge} wraps an {@link AIJudgeConfig} and a {@link Runner}. Each call to
 * {@link #evaluate} or {@link #evaluateMessages} invokes the runner with a formatted evaluation
 * prompt and parses the structured {@code {score, reasoning}} response. Evaluation can be sampled
 * to reduce cost: pass a {@code samplingRate} of {@code 0.0} to always skip, or {@code 1.0} to
 * always run.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class Judge {
  /**
   * JSON-Schema fragment sent to the runner as the {@code outputType}, requesting structured
   * {@code {score, reasoning}} output.
   */
  private static final Map<String, Object> EVALUATION_SCHEMA;
  static {
    Map<String, Object> scoreSchema = new HashMap<>();
    scoreSchema.put("type", "number");
    scoreSchema.put("minimum", 0);
    scoreSchema.put("maximum", 1);
    scoreSchema.put("description", "Score between 0.0 and 1.0.");

    Map<String, Object> reasoningSchema = new HashMap<>();
    reasoningSchema.put("type", "string");
    reasoningSchema.put("description", "Reasoning behind the score.");

    Map<String, Object> properties = new HashMap<>();
    properties.put("score", Collections.unmodifiableMap(scoreSchema));
    properties.put("reasoning", Collections.unmodifiableMap(reasoningSchema));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("properties", Collections.unmodifiableMap(properties));
    schema.put("required", Arrays.asList("score", "reasoning"));
    schema.put("additionalProperties", false);

    EVALUATION_SCHEMA = Collections.unmodifiableMap(schema);
  }

  private final AIJudgeConfig config;
  private final Runner runner;
  private final LDLogger logger;

  /**
   * Constructs a judge.
   *
   * @param config the judge AI Config
   * @param runner the runner to invoke
   * @param logger the logger
   */
  public Judge(AIJudgeConfig config, Runner runner, LDLogger logger) {
    this.config = config;
    this.runner = runner;
    this.logger = logger;
  }

  /**
   * Evaluates the given input/output pair, always running (sampling rate {@code 1.0}).
   *
   * @param input the message history or prompt that was sent to the model
   * @param output the model's response to evaluate
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluate(String input, String output) {
    return evaluate(input, output, 1.0);
  }

  /**
   * Evaluates the given input/output pair, subject to the given sampling rate.
   *
   * @param input the message history or prompt that was sent to the model
   * @param output the model's response to evaluate
   * @param samplingRate the fraction of evaluations to actually run; {@code 0.0} always skips,
   *     {@code 1.0} always runs
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluate(String input, String output, double samplingRate) {
    if (samplingRate <= 0.0) {
      return JudgeResult.builder()
          .sampled(false)
          .success(false)
          .build();
    }
    if (ThreadLocalRandom.current().nextDouble() > samplingRate) {
      return JudgeResult.builder()
          .sampled(false)
          .success(false)
          .build();
    }

    try {
      String formatted = "MESSAGE HISTORY:\n" + input + "\n\nRESPONSE TO EVALUATE:\n" + output;
      LDAIConfigTracker tracker = config.createTracker();

      RunnerResult result = tracker.trackMetricsOf(RunnerResult::getMetrics, () -> runner.run(formatted, EVALUATION_SCHEMA));

      Map<String, Object> parsed = result.getParsed();
      if (parsed == null) {
        if (logger != null) logger.warn("Judge {}: runner returned null parsed output", config.getKey());
        return JudgeResult.builder()
            .sampled(true)
            .success(false)
            .judgeConfigKey(config.getKey())
            .metricKey(config.getEvaluationMetricKey())
            .build();
      }

      Object scoreRaw = parsed.get("score");
      if (!(scoreRaw instanceof Number)) {
        if (logger != null) logger.warn("Judge {}: parsed output missing numeric score", config.getKey());
        return JudgeResult.builder()
            .sampled(true)
            .success(false)
            .judgeConfigKey(config.getKey())
            .metricKey(config.getEvaluationMetricKey())
            .build();
      }
      double score = ((Number) scoreRaw).doubleValue();
      if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
        if (logger != null) logger.warn("Judge {}: score {} is outside [0.0, 1.0]", config.getKey(), score);
        return JudgeResult.builder()
            .sampled(true)
            .success(false)
            .judgeConfigKey(config.getKey())
            .metricKey(config.getEvaluationMetricKey())
            .build();
      }

      JudgeResult.Builder resultBuilder = JudgeResult.builder()
          .sampled(true)
          .success(true)
          .judgeConfigKey(config.getKey())
          .metricKey(config.getEvaluationMetricKey())
          .score(score);

      Object reasoningRaw = parsed.get("reasoning");
      if (reasoningRaw instanceof String) {
        resultBuilder.reasoning((String) reasoningRaw);
      } else if (reasoningRaw != null) {
        if (logger != null) logger.warn("Judge {}: reasoning is not a string, ignoring", config.getKey());
      }

      return resultBuilder.build();
    } catch (Exception ex) {
      return JudgeResult.builder()
          .sampled(true)
          .success(false)
          .judgeConfigKey(config.getKey())
          .metricKey(config.getEvaluationMetricKey())
          .errorMessage(ex.getMessage())
          .build();
    }
  }

  /**
   * Evaluates a message list and runner response, always running (sampling rate {@code 1.0}).
   * <p>
   * Messages are formatted as {@code role: content} lines, joined by newlines.
   *
   * @param messages the messages that were sent to the model
   * @param response the runner result whose {@link RunnerResult#getContent() content} is evaluated
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluateMessages(List<Message> messages, RunnerResult response) {
    return evaluateMessages(messages, response, 1.0);
  }

  /**
   * Evaluates a message list and runner response, subject to the given sampling rate.
   * <p>
   * Messages are formatted as {@code role: content} lines, joined by newlines.
   *
   * @param messages the messages that were sent to the model
   * @param response the runner result whose {@link RunnerResult#getContent() content} is evaluated
   * @param samplingRate the fraction of evaluations to actually run
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluateMessages(List<Message> messages, RunnerResult response, double samplingRate) {
    String formattedMessages = messages == null ? "" : messages.stream()
        .map(m -> m.getRole().getWireValue() + ": " + m.getContent())
        .collect(Collectors.joining("\n"));
    return evaluate(formattedMessages, response == null ? "" : response.getContent(), samplingRate);
  }

  /**
   * Returns the judge AI Config this instance was constructed with.
   *
   * @return the judge config
   */
  public AIJudgeConfig getConfig() {
    return config;
  }

  /**
   * Returns the runner this instance was constructed with.
   *
   * @return the runner
   */
  public Runner getRunner() {
    return runner;
  }
}
