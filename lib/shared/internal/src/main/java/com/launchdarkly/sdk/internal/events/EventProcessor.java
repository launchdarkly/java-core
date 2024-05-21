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
