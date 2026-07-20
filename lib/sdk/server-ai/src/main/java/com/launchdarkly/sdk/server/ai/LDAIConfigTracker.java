package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.FeedbackKind;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.MetricSummary;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TrackData;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Reports events related to a single AI run of an {@link AIConfig}.
 * <p>
 * A tracker is obtained from a retrieved config via {@link AIConfig#createTracker()}, or
 * reconstructed from a resumption token via {@link LDAIClient#createTracker(String, com.launchdarkly.sdk.LDContext)}.
 * Each tracker corresponds to one AI run and is used to record metrics such as model usage,
 * duration, and feedback against the AI Config it was created from.
 * <p>
 * Most tracking methods are at-most-once: a second call to the same method on the same tracker
 * is silently dropped. {@link #trackToolCall(String)} and {@link #trackJudgeResult(JudgeResult)}
 * are multi-fire — each call records a distinct event.
 * <p>
 * Implementations are thread-safe.
 */
public interface LDAIConfigTracker {

  /**
   * Returns the correlation metadata for this tracker's run.
   *
   * @return the track data, never {@code null}
   */
  TrackData getTrackData();

  /**
   * Returns the resumption token for this run.
   * <p>
   * The resumption token encodes the run's identity and can be passed to
   * {@link LDAIClient#createTracker(String, com.launchdarkly.sdk.LDContext)} to reconstruct a
   * tracker on a subsequent request (for example, in a streaming scenario).
   * <p>
   * <strong>Security note:</strong> resumption tokens embed flag-evaluation details such as the
   * variation key and config version. Keep tokens server-side and do not round-trip them through
   * untrusted clients where they could leak flag-targeting information.
   *
   * @return the resumption token, or {@code null} if not available
   */
  String getResumptionToken();

  /**
   * Records the duration of the AI generation.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped.
   *
   * @param duration the duration; ignored if {@code null}
   */
  void trackDuration(Duration duration);

  /**
   * Executes the given operation and records its wall-clock duration.
   * <p>
   * The duration is recorded even if the operation throws. Equivalent to wrapping the operation
   * in a try/finally that calls {@link #trackDuration(Duration)}.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute and time; must not be {@code null}
   * @return the result of the operation
   * @throws Exception if the operation throws
   */
  <T> T trackDurationOf(Callable<T> operation) throws Exception;

  /**
   * Records the time from request start to receipt of the first token.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped.
   *
   * @param duration the time to first token; ignored if {@code null}
   */
  void trackTimeToFirstToken(Duration duration);

  /**
   * Records that the AI generation succeeded.
   * <p>
   * At-most-once and mutually exclusive with {@link #trackError()}: whichever is called first wins.
   */
  void trackSuccess();

  /**
   * Records that the AI generation failed.
   * <p>
   * At-most-once and mutually exclusive with {@link #trackSuccess()}: whichever is called first wins.
   */
  void trackError();

  /**
   * Records user feedback for this AI generation.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped.
   *
   * @param kind the feedback kind; ignored if {@code null}
   */
  void trackFeedback(FeedbackKind kind);

  /**
   * Records token usage for this AI generation.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped. Calls where all
   * counts are zero do not consume the at-most-once slot.
   *
   * @param tokens the token usage; ignored if {@code null}
   */
  void trackTokens(TokenUsage tokens);

  /**
   * Records a single tool call made during this AI generation.
   * <p>
   * Multi-fire: every call emits an event.
   *
   * @param toolKey the tool key; ignored if {@code null}
   */
  void trackToolCall(String toolKey);

  /**
   * Records multiple tool calls made during this AI generation.
   * <p>
   * Equivalent to calling {@link #trackToolCall(String)} for each key.
   *
   * @param toolKeys the tool keys; ignored if {@code null}
   */
  void trackToolCalls(List<String> toolKeys);

  /**
   * Records the result of a judge evaluation.
   * <p>
   * Multi-fire per judge metric key. The result is silently skipped if it was not sampled, if
   * the evaluation did not succeed, or if the metric key or score is absent.
   *
   * @param result the judge result; ignored if {@code null}
   */
  void trackJudgeResult(JudgeResult result);

  /**
   * Executes the given operation and tracks its metrics using the extracted {@link AIMetrics}.
   * <p>
   * Tracks duration (preferring runner-reported duration when present), success or error, tokens,
   * and tool calls. If the operation throws, {@link #trackError()} is called and the exception
   * is re-thrown.
   *
   * @param <T> the return type of the operation
   * @param metricsExtractor a function that extracts {@link AIMetrics} from the operation result;
   *     exceptions from the extractor propagate to the caller
   * @param operation the AI operation to execute; must not be {@code null}
   * @return the result of the operation
   * @throws Exception if the operation or the metrics extractor throws
   */
  <T> T trackMetricsOf(
      Function<? super T, AIMetrics> metricsExtractor,
      Callable<T> operation) throws Exception;

  /**
   * Returns a snapshot of all metrics tracked so far on this tracker.
   *
   * @return the metric summary, never {@code null}
   */
  MetricSummary getSummary();
}
