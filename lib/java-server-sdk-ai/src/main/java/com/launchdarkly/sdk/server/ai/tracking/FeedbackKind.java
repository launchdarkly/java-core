package com.launchdarkly.sdk.server.ai.tracking;

/**
 * The kinds of user feedback that can be recorded for an AI run.
 */
public enum FeedbackKind {
  /** Positive sentiment. */
  POSITIVE("positive"),
  /** Negative sentiment. */
  NEGATIVE("negative");

  private final String value;

  FeedbackKind(String value) {
    this.value = value;
  }

  /**
   * Returns the wire value of this feedback kind.
   *
   * @return {@code "positive"} or {@code "negative"}
   */
  public String getValue() {
    return value;
  }
}
