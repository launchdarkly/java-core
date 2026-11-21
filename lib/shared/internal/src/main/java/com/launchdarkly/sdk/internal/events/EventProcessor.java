package com.launchdarkly.sdk.internal.events;

/**
 * Interface describing the {@link DefaultEventProcessor} methods. There will normally only ever
 * be one implementation of this, but having an interface allows for mocking in tests.
 */
public interface EventProcessor {
  /**
   * Enqueues an event.
   * 
   * @param e the input data
   */
  void sendEvent(Event e);
  
  /**
   * Schedules an asynchronous flush.
   */
  void flushAsync();
  
  /**
   * Flushes and blocks until the flush is done.
   */
  void flushBlocking();

  /**
   * Flushes and blocks until the events buffered before this call have been delivered, or until the
   * timeout elapses. Unlike {@link #flushBlocking()}, which returns once the flush has been handed to
   * a delivery thread, this waits for every delivery in progress to finish, so it suits an
   * application that is about to exit.
   * <p>
   * The default implementation calls {@link #flushBlocking()} and returns true, for implementations
   * that predate this method.
   *
   * @param timeoutMillis the longest time to wait, in milliseconds; zero or less means don't wait
   * @return true if delivery finished in time, false if the timeout elapsed first or the event
   *   processor was closed
   */
  default boolean flushBlocking(long timeoutMillis) {
    flushBlocking();
    return true;
  }
  
  /**
   * Tells the event processor whether we should be in background mode. This is only applicable in the client-side
   * (Android) SDK. In background mode, events mostly work the same but we do not send any periodic diagnostic events.
   * 
   * @param inBackground true if we should be in background mode
   */
  void setInBackground(boolean inBackground);
  
  /**
   * Tells the event processor whether we should be in background mode. This is only applicable in the client-side
   * (Android) SDK; in the server-side Java SDK, offline mode does not change dynamically and so we don't even
   * bother to create an event processor if we're offline. In offline mode, events are enqueued but never flushed,
   * and diagnostic events are not sent.
   * 
   * @param offline true if we should be in offline mode
   */
  void setOffline(boolean offline);
}
