package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.Metrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Reports metrics related to a single AI run of an {@link AIConfig}.
 * <p>
 * A tracker is obtained from a retrieved config via {@link AIConfig#createTracker()}, or
 * reconstructed across process boundaries via
 * {@link LDAIClient#createTracker(String, com.launchdarkly.sdk.LDContext)}. Each tracker corresponds
 * to one AI run; every event it emits shares a {@code runId} (a UUIDv4) so LaunchDarkly can
 * correlate them in metrics views. Start a new run by calling {@link AIConfig#createTracker()} again.
 * <p>
 * <strong>Thread-safety.</strong> Implementations are safe to share across threads. The
 * "record-once" metrics ({@link #trackDuration}, {@link #trackTimeToFirstToken},
 * {@link #trackSuccess}/{@link #trackError}, {@link #trackFeedback}, {@link #trackTokens}) each emit
 * at most once per tracker even under concurrent calls; later calls are ignored and logged.
 * {@link #trackToolCall}/{@link #trackToolCalls} and {@link #trackJudgeResult} may be called any
 * number of times and emit on every call.
 */
public interface LDAIConfigTracker {
  /**
   * Returns the correlation data attached to every event this tracker emits.
   *
   * @return the track data, never {@code null}
   */
  TrackData getTrackData();

  /**
   * Returns a URL-safe Base64 token that encodes this tracker's {@code runId}, {@code configKey},
   * {@code variationKey}, and {@code version}.
   * <p>
   * Pass it to {@link LDAIClient#createTracker(String, com.launchdarkly.sdk.LDContext)} to
   * reconstruct a tracker in another process so deferred events (for example user feedback) still
   * correlate with the original run.
   *
   * @return the resumption token, never {@code null}
   */
  String getResumptionToken();

  /**
   * Records the duration of the generation.
   * <p>
   * Records at most once per tracker; later calls are ignored. Negative durations (for example from
   * clock skew) are clamped to zero.
   *
   * @param duration the generation duration; must not be {@code null}
   */
  void trackDuration(Duration duration);

  /**
   * Runs the given operation, recording its duration even if it throws.
   * <p>
   * This does not record success or error; use {@link #trackMetricsOf} for that. Because
   * {@link #trackDuration} records at most once, calling this twice on the same tracker re-runs the
   * operation but emits no second duration event.
   *
   * @param operation the operation to time
   * @param <T> the operation's result type
   * @return the operation's result
   * @throws Exception if the operation throws
   */
  <T> T trackDurationOf(Callable<T> operation) throws Exception;

  /**
   * Records the time to first token for a streaming generation.
   * <p>
   * Records at most once per tracker; later calls are ignored. Negative values are clamped to zero.
   *
   * @param duration the time to first token; must not be {@code null}
   */
  void trackTimeToFirstToken(Duration duration);

  /**
   * Records that the generation succeeded.
   * <p>
   * Success and error share state: only the first of {@link #trackSuccess}/{@link #trackError}
   * recorded on a tracker takes effect; later calls are ignored.
   */
  void trackSuccess();

  /**
   * Records that the generation failed.
   * <p>
   * Success and error share state: only the first of {@link #trackSuccess}/{@link #trackError}
   * recorded on a tracker takes effect; later calls are ignored.
   */
  void trackError();

  /**
   * Records end-user feedback about the generation.
   * <p>
   * Records at most once per tracker; later calls are ignored.
   *
   * @param kind the feedback sentiment; must not be {@code null}
   */
  void trackFeedback(FeedbackKind kind);

  /**
   * Records token usage for the generation.
   * <p>
   * Records at most once per tracker; later calls are ignored. Negative counts are clamped to zero,
   * and an individual count is only emitted when it is greater than zero.
   *
   * @param tokens the token usage; must not be {@code null}
   */
  void trackTokens(TokenUsage tokens);

  /**
   * Records a single tool invocation. May be called any number of times.
   *
   * @param toolKey the identifier of the invoked tool; must not be {@code null}
   */
  void trackToolCall(String toolKey);

  /**
   * Records several tool invocations. May be called any number of times.
   *
   * @param toolKeys the identifiers of the invoked tools; must not be {@code null}
   */
  void trackToolCalls(List<String> toolKeys);

  /**
   * Records a judge evaluation result. May be called any number of times.
   * <p>
   * No event is emitted when the result was not sampled, did not succeed, or carries no metric key
   * or score. A {@code null} score is treated as "no score" and is distinct from {@code 0.0}.
   *
   * @param result the judge result; must not be {@code null}
   */
  void trackJudgeResult(JudgeResult result);

  /**
   * Runs the given operation, recording its duration and then its outcome and metrics.
   * <p>
   * The operation is timed via {@link #trackDurationOf}. If it throws, an error is recorded and the
   * exception is rethrown. Otherwise the extractor is applied to the result; if the extractor
   * throws, an error is recorded and the exception is rethrown. On success the extracted metrics
   * drive {@link #trackSuccess}/{@link #trackError}, {@link #trackTokens}, and
   * {@link #trackToolCalls}.
   *
   * @param metricsExtractor extracts {@link Metrics} from the operation's result
   * @param operation the AI operation to run
   * @param <T> the operation's result type
   * @return the operation's result
   * @throws Exception if the operation or the extractor throws
   */
  <T> T trackMetricsOf(Function<? super T, Metrics> metricsExtractor, Callable<T> operation)
      throws Exception;

  /**
   * Returns an immutable snapshot of the metrics recorded on this tracker so far.
   *
   * @return the metric summary, never {@code null}
   */
  MetricSummary getSummary();
}
