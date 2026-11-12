package com.launchdarkly.sdk.internal.events;

import java.io.Closeable;
import java.net.URI;
import java.util.Date;

/**
 * Internal interface for a component that can deliver preformatted event data.
 * <p>
 * This is separate from the public EventSender interface in the SDK that applications can use to
 * provide a custom implementation. The latter is used as a wrapper for this one, so we do not have
 * to expose any types from the internal events code. The public interface is simpler because it
 * only needs to return a success or failure classification; the use of the Date header is an
 * implementation detail that is specific to the default HTTP implementation of event delivery.
 */
public interface EventSender extends Closeable {
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
   * @param data the preformatted JSON data, as a string
   * @param eventsBaseUri the configured events endpoint base URI
   * @return a {@link Result}
   */
  Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri);
  
  /**
   * Encapsulates the results of a call to an EventSender method.
   */
  public static final class Result {
    private final boolean success;
    private final boolean mustShutDown;
    private final boolean unexpected;
    private final Date timeFromServer;

    private Result(boolean success, boolean mustShutDown, boolean unexpected, Date timeFromServer) {
      this.success = success;
      this.mustShutDown = mustShutDown;
      this.unexpected = unexpected;
      this.timeFromServer = timeFromServer;
    }

    /**
     * Constructs an instance.
     *
     * @param success true if the events were delivered
     * @param mustShutDown true if the failure was an unexpected error (such as an HTTP 401 error,
     *   implying that the SDK key is invalid)
     * @param timeFromServer the parsed value of an HTTP Date header received from the remote server,
     *   if any; this is used to compensate for differences between the application's time and server time
     *
     * @deprecated Use {@link #success(Date)}, {@link #failure()}, or {@link #unexpectedFailure()}.
     *   The {@code mustShutDown} flag predates the delivery cooldown: it is now read through
     *   {@link #isUnexpectedFailure()} and no longer shuts anything down permanently.
     */
    @Deprecated
    public Result(boolean success, boolean mustShutDown, Date timeFromServer) {
      this(success, mustShutDown, false, timeFromServer);
    }

    /**
     * Returns a result representing a successful delivery.
     *
     * @param timeFromServer the parsed value of an HTTP Date header received from the remote
     *   server, if any; may be null
     * @return the result
     */
    public static Result success(Date timeFromServer) {
      return new Result(true, false, false, timeFromServer);
    }

    /**
     * Returns a result representing an ordinary delivery failure, such as a service outage or
     * network error. Unlike {@link #unexpectedFailure()}, it does not cause event delivery to
     * enter a cooldown window.
     *
     * @return the result
     */
    public static Result failure() {
      return new Result(false, false, false, null);
    }

    /**
     * Returns a result representing a delivery failure classified as unexpected (such as an HTTP
     * 401 error, implying that the SDK key is invalid), which makes event delivery back off.
     * <p>
     * For the benefit of consumers that still read {@link #isMustShutDown()}, this also sets that
     * legacy flag.
     *
     * @return the result
     */
    public static Result unexpectedFailure() {
      return new Result(false, true, true, null);
    }

    /**
     * Returns true if the events were delivered.
     *
     * @return true if the events were delivered
     */
    public boolean isSuccess() {
      return success;
    }

    /**
     * Returns true if the failure was an unexpected error (such as an HTTP 401 error, implying
     * that the SDK key is invalid).
     *
     * @return true if the failure was unexpected
     *
     * @deprecated Use {@link #isUnexpectedFailure()}. An unexpected failure no longer shuts event
     *   delivery down permanently; it makes delivery back off.
     */
    @Deprecated
    public boolean isMustShutDown() {
      return mustShutDown;
    }

    /**
     * Returns true if the failure was classified as unexpected (such as an HTTP 401 error,
     * implying that the SDK key is invalid). Such a failure makes event delivery back off.
     * <p>
     * This is true if the result was constructed with {@link #unexpectedFailure()}, or if it
     * carries the legacy shutdown flag, so results from senders written before this
     * classification existed are interpreted correctly.
     *
     * @return true if the failure was unexpected
     */
    public boolean isUnexpectedFailure() {
      return unexpected || mustShutDown;
    }

    /**
     * Returns the parsed value of an HTTP Date header received from the remote server, if any. This
     * is used to compensate for differences between the application's time and server time.
     *
     * @return a date value or null
     */
    public Date getTimeFromServer() {
      return timeFromServer;
    }
  }
}
