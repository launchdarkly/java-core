package com.launchdarkly.sdk.server.integrations;

import java.time.Duration;

/**
 * Passed to {@link Hook#eventFlushCompleted(EventFlushContext)} after each attempt to deliver a
 * batch of analytics events.
 *
 * @since 7.18.0
 */
public final class EventFlushContext {
  private final int eventCount;
  private final long payloadSize;
  private final boolean success;
  private final int statusCode;
  private final Duration duration;
  private final long droppedCount;

  /**
   * Creates an instance.
   *
   * @param eventCount the number of events in the batch; a summary event counts as one event
   * @param payloadSize the size of the serialized payload in bytes, before compression
   * @param success true if the events service accepted the batch
   * @param statusCode the HTTP status code of the last response, or 0 if no response was received
   * @param duration the time spent on the delivery attempt, including retries
   * @param droppedCount the number of events discarded before delivery since the previous invocation
   */
  public EventFlushContext(int eventCount, long payloadSize, boolean success, int statusCode,
      Duration duration, long droppedCount) {
    this.eventCount = eventCount;
    this.payloadSize = payloadSize;
    this.success = success;
    this.statusCode = statusCode;
    this.duration = duration;
    this.droppedCount = droppedCount;
  }

  /**
   * @return the number of events in the batch; a summary event counts as one event
   */
  public int getEventCount() {
    return eventCount;
  }

  /**
   * @return the size of the serialized payload in bytes, before compression
   */
  public long getPayloadSize() {
    return payloadSize;
  }

  /**
   * @return true if the events service accepted the batch
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * @return the HTTP status code of the last response, or 0 if no response was received
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * @return the time spent on the delivery attempt, including retries
   */
  public Duration getDuration() {
    return duration;
  }

  /**
   * @return the number of events discarded before delivery since the previous invocation
   */
  public long getDroppedCount() {
    return droppedCount;
  }
}
