package com.launchdarkly.sdk.server.ai.tracking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A summary of the metrics that have been tracked by a single {@link AIConfigTracker}.
 * <p>
 * Each metric (duration, success/error, feedback, tokens, time-to-first-token) is recorded at most
 * once per tracker, so this summary reflects a single set of data for the tracker's run. The
 * summary also carries the tracker's {@link #getResumptionToken() resumption token}, captured at
 * construction.
 */
public final class MetricSummary {
  private Long durationMs;
  private Boolean success;
  private FeedbackKind feedback;
  private TokenUsage tokens;
  private Long timeToFirstTokenMs;
  private final List<String> toolCalls = new ArrayList<>();
  private String resumptionToken;

  MetricSummary() {
  }

  /**
   * Returns the tracked duration.
   *
   * @return the duration in milliseconds, or {@code null} if not tracked
   */
  public Long getDurationMs() {
    return durationMs;
  }

  /**
   * Returns the tracked success status.
   *
   * @return {@code true}/{@code false} if a success or error was tracked, or {@code null} if neither
   */
  public Boolean getSuccess() {
    return success;
  }

  /**
   * Returns the tracked user feedback.
   *
   * @return the feedback kind, or {@code null} if not tracked
   */
  public FeedbackKind getFeedback() {
    return feedback;
  }

  /**
   * Returns the tracked token usage.
   *
   * @return the token usage, or {@code null} if not tracked
   */
  public TokenUsage getTokens() {
    return tokens;
  }

  /**
   * Returns the tracked time to first token.
   *
   * @return the time to first token in milliseconds, or {@code null} if not tracked
   */
  public Long getTimeToFirstTokenMs() {
    return timeToFirstTokenMs;
  }

  /**
   * Returns the tool calls tracked during this run.
   *
   * @return an unmodifiable list of tool keys (never {@code null})
   */
  public List<String> getToolCalls() {
    return Collections.unmodifiableList(toolCalls);
  }

  /**
   * Returns the resumption token for the tracker that produced this summary.
   *
   * @return the URL-safe Base64-encoded resumption token, or {@code null} if tracker construction failed
   */
  public String getResumptionToken() {
    return resumptionToken;
  }

  void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }

  void setSuccess(boolean success) {
    this.success = success;
  }

  void setFeedback(FeedbackKind feedback) {
    this.feedback = feedback;
  }

  void setTokens(TokenUsage tokens) {
    this.tokens = tokens;
  }

  void setTimeToFirstTokenMs(long timeToFirstTokenMs) {
    this.timeToFirstTokenMs = timeToFirstTokenMs;
  }

  void addToolCall(String toolKey) {
    this.toolCalls.add(toolKey);
  }

  void setResumptionToken(String resumptionToken) {
    this.resumptionToken = resumptionToken;
  }
}
