package com.launchdarkly.sdk.server.subsystems;

import java.io.Closeable;
import java.net.URI;

/**
 * Interface for a component that can deliver preformatted event data.
 * <p>
 * By default, the SDK sends event data to the LaunchDarkly events service via HTTP. You may
 * provide a different implementation of event delivery by implementing this interface-- for
 * instance, to create a test fixture, or to store the data somewhere else.
 * 
 * @see com.launchdarkly.sdk.server.integrations.EventProcessorBuilder#eventSender(ComponentConfigurer)
 * @since 4.14.0
 */
public interface EventSender extends Closeable {
  /**
   * Result type for event sending methods.
   */
  public enum Result {
    /**
     * The EventSender successfully delivered the event(s).
     */
    SUCCESS,
    
    /**
     * The EventSender was not able to deliver the events.
     */
    FAILURE,
    
    /**
     * The EventSender was not able to deliver the events, and the nature of the error (such as an
     * HTTP 401 error, implying that the SDK key is invalid) indicates that the SDK should stop
     * attempting delivery for a while.
     * <p>
     * The SDK responds by entering a bounded cooldown window. While the window is open, the SDK
     * initiates no new analytics-event deliveries through this EventSender (a delivery already
     * dispatched when the window opened still completes), and analytics events that would have
     * been sent during the window are discarded rather than held. Diagnostic-event calls are not
     * suppressed, and this result from one of them opens a window of its own. If delivery is
     * still failing this way when a window elapses, the next window is longer, up to a ceiling;
     * sustained successful delivery returns it to the initial length. Delivery resumes on its own
     * once a window has elapsed; no application action is required, and event delivery is never
     * disabled permanently. Returning {@link #FAILURE} instead never opens or lengthens a window.
     */
    STOP
  };
  
  /**
   * Attempt to deliver an analytics event data payload.
   * <p>
   * This method will be called synchronously from an event delivery worker thread. 
   * 
   * @param data the preformatted JSON data, in UTF-8 encoding
   * @param eventCount the number of individual events in the data
   * @param eventsBaseUri the configured events endpoint base URI
   * @return a {@link Result}
   */
  Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri);

  /**
   * Attempt to deliver a diagnostic event data payload.
   * <p>
   * This method will be called synchronously from an event delivery worker thread. 
   * 
   * @param data the preformatted JSON data, in UTF-8 encoding
   * @param eventsBaseUri the configured events endpoint base URI
   * @return a {@link Result}
   */
  Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri);
}
