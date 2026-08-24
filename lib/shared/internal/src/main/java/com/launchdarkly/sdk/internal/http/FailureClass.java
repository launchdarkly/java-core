package com.launchdarkly.sdk.internal.http;

import javax.net.ssl.SSLException;

import java.security.GeneralSecurityException;

/**
 * Classifies a failure into one of two regimes: {@link #NORMAL} or
 * {@link #UNEXPECTED}. Used by data sources and other network-facing components
 * to decide whether a failure should trigger extended-regime backoff.
 * <p>
 * This class is for internal use only and should not be documented in the SDK API.
 * It is not supported for any use outside of the LaunchDarkly SDKs, and is subject
 * to change without notice.
 */
public enum FailureClass {
  /**
   * Ordinary transient failure. Use the normal-regime backoff. Includes HTTP
   * 400 / 408 / 429, HTTP 5xx, any other HTTP status the SDK treats as a
   * failure, and generic transport failures (connection refused, read timeout,
   * DNS failure, etc.).
   */
  NORMAL,

  /**
   * Unexpected failure indicative of a longer-lived condition. Use the
   * extended-regime backoff. Includes HTTP 401 / 403 and any other 4xx not in
   * the NORMAL list, plus TLS / certificate validation failures.
   */
  UNEXPECTED;

  /**
   * Scans an exception chain for TLS / certificate validation causes.
   */
  static boolean hasTlsOrCertificateCause(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SSLException
          || c instanceof GeneralSecurityException) {
        return true;
      }
    }
    return false;
  }
}
