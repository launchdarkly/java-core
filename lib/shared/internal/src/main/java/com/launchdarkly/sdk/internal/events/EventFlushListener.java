package com.launchdarkly.sdk.internal.events;

/**
 * Receives the outcome of each attempt to deliver a batch of analytics events. The SDK uses it to
 * report event delivery to hooks.
 * <p>
 * This interface is not exposed in the public SDK API.
 */
public interface EventFlushListener {
  /**
   * Called once after each delivery attempt has completed. Two attempts can run at the same time,
   * so this method can be called from more than one thread at once.
   *
   * @param result the outcome of the attempt
   */
  void flushCompleted(EventFlushResult result);
}
