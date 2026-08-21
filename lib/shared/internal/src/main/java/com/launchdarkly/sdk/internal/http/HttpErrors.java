package com.launchdarkly.sdk.internal.http;

import com.launchdarkly.logging.LDLogger;

/**
 * Contains shared helpers related to HTTP response validation.
 * <p>
 * This class is for internal use only and should not be documented in the SDK API. It is not
 * supported for any use outside of the LaunchDarkly SDKs, and is subject to change without notice.
 */
public abstract class HttpErrors {
  private HttpErrors() {}

  /**
   * Represents an HTTP response error as an exception.
   */
  @SuppressWarnings("serial")
  public static final class HttpErrorException extends Exception {
    private final int status;

    /**
     * Constructs an instance.
     * @param status the status code
     */
    public HttpErrorException(int status) {
      super("HTTP error " + status);
      this.status = status;
    }

    /**
     * Returns the status code.
     * @return the status code
     */
    public int getStatus() {
      return status;
    }
  }

  /**
   * Tests whether an HTTP error status represents a condition that might resolve on its own if we retry.
   * @param statusCode the HTTP status
   * @return true if retrying makes sense; false if it should be considered a permanent failure
   *
   * @deprecated Prefer {@link #classifyHTTPFailure(int)}, which returns a {@link FailureClass}
   *     that lets the caller distinguish an extended-regime backoff signal from an ordinary
   *     transient failure. This boolean method treats {@code false} as "give up permanently",
   *     which does not fit callers that keep retrying regardless of classification.
   */
  @Deprecated
  public static boolean isHttpErrorRecoverable(int statusCode) {
    if (statusCode >= 400 && statusCode < 500) {
      switch (statusCode) {
      case 400: // bad request
      case 408: // request timeout
      case 429: // too many requests
        return true;
      default:
        return false; // all other 4xx errors are unrecoverable
      }
    }
    return true;
  }

  /**
   * Logs an HTTP error or network error at the appropriate level and determines whether it is recoverable
   * (as defined by {@link #isHttpErrorRecoverable(int)}).
   *
   * @param logger the logger to log to
   * @param errorDesc description of the error
   * @param errorContext a phrase like "when doing such-and-such"
   * @param statusCode HTTP status code, or 0 for a network error
   * @param recoverableMessage a phrase like "will retry" to use if the error is recoverable
   * @return true if the error is recoverable
   *
   * @deprecated Prefer {@link #classifyAndLogHTTPFailure} and
   *     {@link #classifyAndLogTransportFailure}, which return a {@link FailureClass} that lets
   *     the caller distinguish an extended-regime backoff signal from an ordinary transient
   *     failure. This method treats a {@code false} return as "give up permanently", which does
   *     not fit callers that keep retrying regardless of classification.
   */
  @Deprecated
  public static boolean checkIfErrorIsRecoverableAndLog(
      LDLogger logger,
      String errorDesc,
      String errorContext,
      int statusCode,
      String recoverableMessage
      ) {
    if (statusCode > 0 && !isHttpErrorRecoverable(statusCode)) {
      logger.error("Error {} (giving up permanently): {}", errorContext, errorDesc);
      return false;
    } else {
      logger.warn("Error {} ({}): {}", errorContext, recoverableMessage, errorDesc);
      return true;
    }
  }

  /**
   * Returns a text description of an HTTP error.
   *
   * @param statusCode the status code
   * @return the error description
   */
  public static String httpErrorDescription(int statusCode) {
    return "HTTP error " + statusCode +
        (statusCode == 401 || statusCode == 403 ? " (invalid SDK key)" : "");
  }

  /**
   * Classifies an HTTP response by its status code. Returns
   * {@link FailureClass#UNEXPECTED} for 401 / 403 and any other 4xx not in the NORMAL list;
   * returns {@link FailureClass#NORMAL} for 400 / 408 / 429, 5xx, and any other status the SDK
   * treats as a failure.
   *
   * @param statusCode the HTTP status code
   * @return the classification
   */
  public static FailureClass classifyHTTPFailure(int statusCode) {
    if (statusCode == 400 || statusCode == 408 || statusCode == 429) {
      return FailureClass.NORMAL;
    }
    if (statusCode >= 500) {
      return FailureClass.NORMAL;
    }
    if (statusCode >= 400 && statusCode < 500) {
      return FailureClass.UNEXPECTED;
    }
    return FailureClass.NORMAL;
  }

  /**
   * Classifies a transport-level exception. TLS or certificate validation failures anywhere in
   * the exception chain are {@link FailureClass#UNEXPECTED}; all other transport failures are
   * {@link FailureClass#NORMAL}.
   *
   * @param t the transport-level exception
   * @return the classification
   */
  public static FailureClass classifyTransportFailure(Throwable t) {
    return FailureClass.hasTlsOrCertificateCause(t) ? FailureClass.UNEXPECTED : FailureClass.NORMAL;
  }

  /**
   * Classifies an HTTP failure per {@link #classifyHTTPFailure(int)}, logs it at the appropriate
   * level, and returns the classification for the caller to act on. Unexpected classifications
   * log at Error since they typically indicate a customer-side problem (invalid or expired SDK
   * key, misconfiguration); normal classifications log at Warn since they are typically transient.
   *
   * @param logger the logger to log to
   * @param statusCode the HTTP status
   * @param errorContext a phrase like "in stream connection" or "on polling request"
   * @param willRetryMessage a phrase like "will retry" or "will retry at next scheduled poll interval"
   * @return the classification
   */
  public static FailureClass classifyAndLogHTTPFailure(
      LDLogger logger,
      int statusCode,
      String errorContext,
      String willRetryMessage
      ) {
    FailureClass failureClass = classifyHTTPFailure(statusCode);
    String errorDesc = httpErrorDescription(statusCode);
    if (failureClass == FailureClass.UNEXPECTED) {
      logger.error("Error {} ({}): {}", errorContext, willRetryMessage, errorDesc);
    } else {
      logger.warn("Error {} ({}): {}", errorContext, willRetryMessage, errorDesc);
    }
    return failureClass;
  }

  /**
   * Classifies a transport failure per {@link #classifyTransportFailure(Throwable)}, logs it at
   * the appropriate level, and returns the classification. Unexpected classifications (TLS /
   * certificate validation) log at Error since they typically indicate a customer-side problem
   * (misconfigured trust store, expired cert); other transport failures log at Warn since they
   * are typically transient.
   *
   * @param logger the logger to log to
   * @param e the transport-level exception
   * @param errorContext a phrase like "in stream connection" or "on polling request"
   * @param willRetryMessage a phrase like "will retry" or "will retry at next scheduled poll interval"
   * @return the classification
   */
  public static FailureClass classifyAndLogTransportFailure(
      LDLogger logger,
      Throwable e,
      String errorContext,
      String willRetryMessage
      ) {
    FailureClass failureClass = classifyTransportFailure(e);
    if (failureClass == FailureClass.UNEXPECTED) {
      logger.error("Error {} ({}): {}", errorContext, willRetryMessage, e.toString());
    } else {
      logger.warn("Error {} ({}): {}", errorContext, willRetryMessage, e.toString());
    }
    return failureClass;
  }
}
