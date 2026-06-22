package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
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
 * The no-op {@link LDAIConfigTracker} used when tracking is not applicable (for example, for
 * disabled configs or in testing contexts). It is immutable and stateless, so a single shared
 * instance is safe to reuse.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class NoOpAIConfigTracker implements LDAIConfigTracker {
  /**
   * The shared instance.
   */
  public static final NoOpAIConfigTracker INSTANCE = new NoOpAIConfigTracker();

  private static final TrackData EMPTY_TRACK_DATA = new TrackData("", "", null, 0, "", "", null);
  private static final MetricSummary EMPTY_SUMMARY =
      new MetricSummary(null, null, null, null, null, null, null);

  private NoOpAIConfigTracker() {
  }

  @Override
  public TrackData getTrackData() {
    return EMPTY_TRACK_DATA;
  }

  @Override
  public String getResumptionToken() {
    return null;
  }

  @Override
  public void trackDuration(Duration duration) {
  }

  @Override
  public <T> T trackDurationOf(Callable<T> operation) throws Exception {
    return operation.call();
  }

  @Override
  public void trackTimeToFirstToken(Duration duration) {
  }

  @Override
  public void trackSuccess() {
  }

  @Override
  public void trackError() {
  }

  @Override
  public void trackFeedback(FeedbackKind kind) {
  }

  @Override
  public void trackTokens(TokenUsage tokens) {
  }

  @Override
  public void trackToolCall(String toolKey) {
  }

  @Override
  public void trackToolCalls(List<String> toolKeys) {
  }

  @Override
  public void trackJudgeResult(JudgeResult result) {
  }

  @Override
  public <T> T trackMetricsOf(
      Function<? super T, AIMetrics> metricsExtractor,
      Callable<T> operation) throws Exception {
    return operation.call();
  }

  @Override
  public MetricSummary getSummary() {
    return EMPTY_SUMMARY;
  }
}
