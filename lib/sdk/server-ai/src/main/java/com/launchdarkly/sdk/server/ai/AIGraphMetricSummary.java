package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;

import java.util.List;

/**
 * A snapshot of the metrics tracked so far by an {@link AIGraphTracker}.
 * <p>
 * All fields are nullable: a {@code null} value means the corresponding metric has not been
 * recorded yet on the tracker. {@link #getResumptionToken()} is always present.
 * <p>
 * Instances are immutable.
 */
public final class AIGraphMetricSummary {
  private final Boolean success;
  private final Double durationMs;
  private final TokenUsage tokens;
  private final List<String> path;
  private final String resumptionToken;

  AIGraphMetricSummary(
      Boolean success,
      Double durationMs,
      TokenUsage tokens,
      List<String> path,
      String resumptionToken) {
    this.success = success;
    this.durationMs = durationMs;
    this.tokens = tokens;
    this.path = path;
    this.resumptionToken = resumptionToken;
  }

  /**
   * Returns the invocation outcome: {@code true} if {@code trackInvocationSuccess} was called,
   * {@code false} if {@code trackInvocationFailure} was called, or {@code null} if neither has
   * been called yet.
   *
   * @return the success flag, or {@code null} if not yet recorded
   */
  public Boolean getSuccess() {
    return success;
  }

  /**
   * Returns the tracked graph-level duration in milliseconds, or {@code null} if not recorded.
   *
   * @return the duration in ms, or {@code null}
   */
  public Double getDurationMs() {
    return durationMs;
  }

  /**
   * Returns the tracked token usage, or {@code null} if not recorded.
   *
   * @return the token usage, or {@code null}
   */
  public TokenUsage getTokens() {
    return tokens;
  }

  /**
   * Returns the tracked node path (ordered list of node keys visited), or {@code null} if not
   * recorded.
   *
   * @return an unmodifiable list of node keys, or {@code null}
   */
  public List<String> getPath() {
    return path;
  }

  /**
   * Returns the resumption token for this graph run, which can be passed to
   * {@link LDAIClient#createGraphTracker(String, com.launchdarkly.sdk.LDContext)} to reconstruct
   * the tracker on a subsequent request.
   *
   * @return the resumption token; never {@code null}
   */
  public String getResumptionToken() {
    return resumptionToken;
  }
}
