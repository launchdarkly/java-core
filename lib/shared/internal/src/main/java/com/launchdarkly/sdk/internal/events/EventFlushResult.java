package com.launchdarkly.sdk.internal.events;

/**
 * The outcome of one attempt to deliver a batch of analytics events. The event processor reports
 * it to the {@link EventFlushListener} after the attempt, including any retry, has completed.
 * <p>
 * This class is not exposed in the public SDK API.
 */
public final class EventFlushResult {
  private final int eventCount;
  private final int payloadBytes;
  private final boolean success;
  private final int statusCode;
  private final long durationMillis;
  private final long droppedCount;

  /**
   * Creates an instance.
   *
   * @param eventCount the number of events in the batch; a summary event counts as one
   * @param payloadBytes the size of the serialized payload before compression
   * @param success true if the events service accepted the batch
   * @param statusCode the HTTP status of the last response, or 0 if no response was received
   * @param durationMillis the time spent on the attempt, including any retry
   * @param droppedCount the number of events discarded before delivery since the previous attempt
   */
  public EventFlushResult(int eventCount, int payloadBytes, boolean success, int statusCode,
      long durationMillis, long droppedCount) {
    this.eventCount = eventCount;
    this.payloadBytes = payloadBytes;
    this.success = success;
    this.statusCode = statusCode;
    this.durationMillis = durationMillis;
    this.droppedCount = droppedCount;
  }

  /**
   * @return the number of events in the batch
   */
  public int getEventCount() {
    return eventCount;
  }

  /**
   * @return the size of the serialized payload before compression
   */
  public int getPayloadBytes() {
    return payloadBytes;
  }

  /**
   * @return true if the events service accepted the batch
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * @return the HTTP status of the last response, or 0 if no response was received
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * @return the time spent on the attempt in milliseconds
   */
  public long getDurationMillis() {
    return durationMillis;
  }

  /**
   * @return the number of events discarded before delivery since the previous attempt
   */
  public long getDroppedCount() {
    return droppedCount;
  }
}
