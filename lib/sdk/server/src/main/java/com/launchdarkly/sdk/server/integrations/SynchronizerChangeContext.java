package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

/**
 * Passed to {@link Hook#synchronizerChanged(SynchronizerChangeContext)} when a synchronizer
 * starts, or when the data system has no synchronizer left to start.
 *
 * @since 7.18.0
 */
public final class SynchronizerChangeContext {
  /**
   * Why the active synchronizer changed.
   */
  public enum Reason {
    /**
     * The first synchronizer started.
     */
    INITIAL("initial"),
    /**
     * The previous synchronizer was unhealthy for too long. The SDK moved to the next one.
     */
    FALLBACK("fallback"),
    /**
     * A fallback synchronizer was healthy for long enough. The SDK returned to the first one.
     */
    RECOVER("recover"),
    /**
     * The previous synchronizer failed permanently. The SDK does not try it again.
     */
    REMOVED("removed"),
    /**
     * LaunchDarkly asked the SDK to use the FDv1 protocol.
     */
    FDV1_FALLBACK("fdv1_fallback"),
    /**
     * No synchronizer is left.
     */
    EXHAUSTED("exhausted");

    private final String value;

    Reason(String value) {
      this.value = value;
    }

    /**
     * @return the lowercase reason name used in telemetry
     */
    public String getValue() {
      return value;
    }
  }

  private final DataSourceDescriptor previous;
  private final DataSourceDescriptor current;
  private final Reason reason;
  private final DataSourceStatusProvider.ErrorInfo lastError;

  /**
   * Creates an instance.
   *
   * @param previous the synchronizer that stopped; empty for the first synchronizer
   * @param current the synchronizer that started; empty when no synchronizer is left
   * @param reason why the change happened
   * @param lastError the most recent data source error at the time of the change, or null
   */
  public SynchronizerChangeContext(DataSourceDescriptor previous, DataSourceDescriptor current,
      Reason reason, DataSourceStatusProvider.ErrorInfo lastError) {
    this.previous = previous == null ? DataSourceDescriptor.empty() : previous;
    this.current = current == null ? DataSourceDescriptor.empty() : current;
    this.reason = reason;
    this.lastError = lastError;
  }

  /**
   * @return the synchronizer that stopped; empty for the first synchronizer
   */
  public DataSourceDescriptor getPrevious() {
    return previous;
  }

  /**
   * @return the synchronizer that started; empty when no synchronizer is left
   */
  public DataSourceDescriptor getCurrent() {
    return current;
  }

  /**
   * @return why the change happened
   */
  public Reason getReason() {
    return reason;
  }

  /**
   * @return the most recent data source error at the time of the change, or null
   */
  public DataSourceStatusProvider.ErrorInfo getLastError() {
    return lastError;
  }
}
