package com.launchdarkly.sdk.fdv2;

/**
 * Indicates whether an FDv2 source result carries a change set or a status update.
 */
public enum SourceResultType {
  /**
   * The source has emitted a change set. This implies that the source is in a valid state.
   */
  CHANGE_SET,

  /**
   * The source is emitting a status update, indicating a transition from being valid to being
   * in some kind of error or non-operational state. The source will emit a {@link #CHANGE_SET}
   * if it becomes valid again.
   */
  STATUS,
}
