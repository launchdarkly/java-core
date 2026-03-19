package com.launchdarkly.sdk.fdv2;

/**
 * Represents the state of an FDv2 data source when emitting a status result.
 */
public enum SourceSignal {
  /**
   * The data source has encountered an interruption and will attempt to reconnect. This is not
   * intended to be used with an initializer; use {@link #TERMINAL_ERROR} instead. When used with
   * an initializer it will still be treated as a terminal state.
   */
  INTERRUPTED,

  /**
   * The data source has been shut down and will not produce any further results.
   */
  SHUTDOWN,

  /**
   * The data source has encountered a terminal error and will not produce any further results.
   */
  TERMINAL_ERROR,

  /**
   * The data source has been instructed to disconnect (e.g. the server sent a goodbye message)
   * and will not produce any further results.
   */
  GOODBYE,
}
