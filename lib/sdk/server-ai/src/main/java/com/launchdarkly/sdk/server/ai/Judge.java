package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;

import java.util.List;
import java.util.Objects;

/**
 * Evaluates the output of another AI Config using a judge AI Config and a caller-supplied
 * {@link Runner}.
 * <p>
 * A judge is an AI Config with {@code mode: judge} that scores a model response. Obtain one from
 * {@link LDAIClient#createJudge}, then call {@link #evaluate(String, String)} with the original
 * input and the response to score. Evaluation is synchronous.
 * <p>
 * The judge records invocation metrics (duration, success, tokens) on its own tracker but does
 * <strong>not</strong> emit the score via {@code trackJudgeResult}; recording the returned
 * {@link JudgeResult} is the caller's responsibility.
 * <p>
 * Instances are immutable and safe to share across threads as long as the supplied {@link Runner}
 * is too.
 */
public final class Judge {
  private final AIJudgeConfig config;
  private final Runner runner;
  private final double sampleRate;
  private final LDLogger logger;

  /**
   * Creates a judge.
   *
   * @param config the judge AI Config; must not be {@code null}
   * @param runner the runner used to invoke the judge model; must not be {@code null}
   * @param sampleRate the default sampling rate in {@code [0.0, 1.0]}; non-finite, negative, or
   *     greater-than-one values are normalized
   * @param logger the logger; must not be {@code null}
   */
  public Judge(AIJudgeConfig config, Runner runner, double sampleRate, LDLogger logger) {
    this.config = Objects.requireNonNull(config, "config");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.sampleRate = normalizeSampleRate(sampleRate);
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * Normalizes a sampling rate into {@code [0.0, 1.0]}. Non-finite rates fall back to {@code 1.0}
   * (the default "always sample"); negative rates clamp to {@code 0.0}; rates above one clamp to
   * {@code 1.0}.
   *
   * @param rate the requested rate
   * @return the normalized rate
   */
  public static double normalizeSampleRate(double rate) {
    if (Double.isNaN(rate) || Double.isInfinite(rate)) {
      return 1.0;
    }
    if (rate < 0.0) {
      return 0.0;
    }
    if (rate > 1.0) {
      return 1.0;
    }
    return rate;
  }

  /**
   * Returns the default sampling rate baked in at construction.
   *
   * @return the sampling rate
   */
  public double getSampleRate() {
    return sampleRate;
  }

  /**
   * Returns the judge AI Config.
   *
   * @return the config
   */
  public AIJudgeConfig getAIConfig() {
    return config;
  }

  /**
   * Returns the runner this judge invokes.
   *
   * @return the runner
   */
  public Runner getRunner() {
    return runner;
  }

  /**
   * Evaluates a response using the judge's default sampling rate.
   *
   * @param input the input that was provided to the AI being evaluated
   * @param output the AI-generated response to score
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluate(String input, String output) {
    return evaluate(input, output, sampleRate);
  }

  /**
   * Evaluates a response, deciding sampling before invoking the model.
   *
   * @param input the input that was provided to the AI being evaluated
   * @param output the AI-generated response to score
   * @param samplingRate the sampling rate to use for this call; an explicit {@code 0} suppresses the
   *     evaluation
   * @return the evaluation result; never {@code null}. The result is failed (and never sampled) when
   *     the judge config has no evaluation metric key, when sampling skips it, when the response
   *     cannot be parsed, or when the runner throws.
   */
  public JudgeResult evaluate(String input, String output, double samplingRate) {
    double effectiveRate = normalizeSampleRate(samplingRate);
    String key = config.getKey();
    LDAIConfigTracker tracker = config.createTracker();
    try {
      String metricKey = evaluationMetricKey();
      if (metricKey == null) {
        logger.warn("Judge configuration is missing required evaluation metric key: {}", key);
        return JudgeResult.builder(true, false)
            .judgeConfigKey(key)
            .errorMessage("Judge configuration is missing required evaluation metric key")
            .build();
      }

      if (Math.random() > effectiveRate) {
        logger.debug("Judge evaluation skipped due to sampling rate: {}", effectiveRate);
        return JudgeResult.builder(false, false).judgeConfigKey(key).build();
      }

      String evaluationInput = buildEvaluationInput(input, output);
      RunnerResult response = tracker.trackMetricsOf(RunnerResult::getMetrics,
          () -> runner.run(evaluationInput));

      ParsedEvaluation parsed = parseEvaluationResponse(response.getParsed());
      if (parsed == null) {
        logger.warn("Could not parse judge evaluation response for: {}", key);
        return JudgeResult.builder(true, false).judgeConfigKey(key).build();
      }

      Metrics metrics = response.getMetrics();
      boolean success = metrics != null && metrics.isSuccess();
      return JudgeResult.builder(true, success)
          .judgeConfigKey(key)
          .metricKey(metricKey)
          .score(parsed.score)
          .reasoning(parsed.reasoning)
          .build();
    } catch (Exception e) {
      logger.error("Judge evaluation failed for {}: {}", key, e.toString());
      String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
      return JudgeResult.builder(true, false).judgeConfigKey(key).errorMessage(message).build();
    }
  }

  /**
   * Evaluates a response from a conversation history and a runner result, using the judge's default
   * sampling rate.
   *
   * @param messages the conversation history; may be empty or {@code null}
   * @param response the runner result whose content is scored
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluateMessages(List<Message> messages, RunnerResult response) {
    return evaluateMessages(messages, response, sampleRate);
  }

  /**
   * Evaluates a response from a conversation history and a runner result.
   * <p>
   * Each message is rendered as {@code <role>: <content>} and the messages are joined with newlines
   * to form the input; the response's content is the output.
   *
   * @param messages the conversation history; may be empty or {@code null}
   * @param response the runner result whose content is scored
   * @param samplingRate the sampling rate to use for this call
   * @return the evaluation result; never {@code null}
   */
  public JudgeResult evaluateMessages(List<Message> messages, RunnerResult response, double samplingRate) {
    StringBuilder input = new StringBuilder();
    if (messages != null) {
      boolean first = true;
      for (Message message : messages) {
        if (!first) {
          input.append('\n');
        }
        input.append(message.getRole().getWireValue()).append(": ").append(message.getContent());
        first = false;
      }
    }
    String output = response == null ? null : response.getContent();
    return evaluate(input.toString(), output, samplingRate);
  }

  private String evaluationMetricKey() {
    String key = config.getEvaluationMetricKey();
    if (key != null && !key.trim().isEmpty()) {
      return key.trim();
    }
    return null;
  }

  private static String buildEvaluationInput(String input, String output) {
    return "MESSAGE HISTORY:\n" + input + "\n\nRESPONSE TO EVALUATE:\n" + output;
  }

  private static ParsedEvaluation parseEvaluationResponse(LDValue parsed) {
    if (parsed == null || parsed.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue score = parsed.get("score");
    if (!score.isNumber()) {
      return null;
    }
    double value = score.doubleValue();
    if (value < 0.0 || value > 1.0) {
      return null;
    }
    LDValue reasoning = parsed.get("reasoning");
    if (!reasoning.isString()) {
      return null;
    }
    return new ParsedEvaluation(value, reasoning.stringValue());
  }

  private static final class ParsedEvaluation {
    private final double score;
    private final String reasoning;

    ParsedEvaluation(double score, String reasoning) {
      this.score = score;
      this.reasoning = reasoning;
    }
  }
}
