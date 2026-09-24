package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import java.time.Duration;

/**
 * Passed to {@link Hook#initializerCompleted(InitializerContext)} after each attempt to obtain
 * data from an initializer.
 *
 * @since 7.18.0
 */
public final class InitializerContext {
  /**
   * How an initializer attempt ended.
   */
  public enum Outcome {
    /**
     * The initializer provided data with a selector. Initialization is complete.
     */
    SUCCEEDED("succeeded"),
    /**
     * The initializer provided data without a selector. The SDK applied the data and continues.
     */
    SUCCEEDED_WITHOUT_SELECTOR("succeeded_without_selector"),
    /**
     * The initializer returned a payload with no usable data.
     */
    NO_DATA("no_data"),
    /**
     * The initializer reported an error.
     */
    FAILED("failed"),
    /**
     * The initializer asked the SDK to fall back to the FDv1 protocol.
     */
    FALLBACK("fallback"),
    /**
     * The SDK closed while the initializer was running.
     */
    CANCELLED("cancelled");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }

    /**
     * @return the lowercase outcome name used in telemetry
     */
    public String getValue() {
      return value;
    }
  }

  private final DataSourceDescriptor dataSource;
  private final Outcome outcome;
  private final DataSourceStatusProvider.ErrorInfo error;
  private final Duration duration;
  private final boolean applied;

  /**
   * Creates an instance.
   *
   * @param dataSource the initializer
   * @param outcome how the attempt ended
   * @param error the error the initializer reported, or null
   * @param duration the time the attempt took
   * @param applied true if the SDK applied data from this initializer to its store
   */
  public InitializerContext(DataSourceDescriptor dataSource, Outcome outcome,
      DataSourceStatusProvider.ErrorInfo error, Duration duration, boolean applied) {
    this.dataSource = dataSource;
    this.outcome = outcome;
    this.error = error;
    this.duration = duration;
    this.applied = applied;
  }

  /**
   * @return the initializer
   */
  public DataSourceDescriptor getDataSource() {
    return dataSource;
  }

  /**
   * @return how the attempt ended
   */
  public Outcome getOutcome() {
    return outcome;
  }

  /**
   * @return the error the initializer reported, or null
   */
  public DataSourceStatusProvider.ErrorInfo getError() {
    return error;
  }

  /**
   * @return the time the attempt took
   */
  public Duration getDuration() {
    return duration;
  }

  /**
   * @return true if the SDK applied data from this initializer to its store
   */
  public boolean isApplied() {
    return applied;
  }
}
