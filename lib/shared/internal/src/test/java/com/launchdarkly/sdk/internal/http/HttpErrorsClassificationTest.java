package com.launchdarkly.sdk.internal.http;

import org.junit.Test;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;

import static com.launchdarkly.sdk.internal.http.FailureClass.NORMAL;
import static com.launchdarkly.sdk.internal.http.FailureClass.UNEXPECTED;
import static org.junit.Assert.assertEquals;

/**
 * Unit coverage for {@link HttpErrors#classifyHttpFailure(int)} and
 * {@link HttpErrors#classifyTransportFailure(Throwable)}.
 */
@SuppressWarnings("javadoc")
public class HttpErrorsClassificationTest {

  // 400, 408, 429 are NORMAL.
  @Test public void http400IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(400)); }
  @Test public void http408IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(408)); }
  @Test public void http429IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(429)); }

  // Other 4xx (including 401, 403) is UNEXPECTED.
  @Test public void http401IsUnexpected()       { assertEquals(UNEXPECTED, HttpErrors.classifyHttpFailure(401)); }
  @Test public void http403IsUnexpected()       { assertEquals(UNEXPECTED, HttpErrors.classifyHttpFailure(403)); }
  @Test public void http404IsUnexpected()       { assertEquals(UNEXPECTED, HttpErrors.classifyHttpFailure(404)); }
  @Test public void http418IsUnexpected()       { assertEquals(UNEXPECTED, HttpErrors.classifyHttpFailure(418)); }
  @Test public void http451IsUnexpected()       { assertEquals(UNEXPECTED, HttpErrors.classifyHttpFailure(451)); }

  // 5xx is NORMAL.
  @Test public void http500IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(500)); }
  @Test public void http502IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(502)); }
  @Test public void http503IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(503)); }
  @Test public void http504IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(504)); }
  @Test public void http599IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(599)); }

  // Unusual non-4xx / non-5xx failure statuses are NORMAL.
  @Test public void http300IsNormal()   { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(300)); }
  @Test public void http0IsNormal()     { assertEquals(NORMAL, HttpErrors.classifyHttpFailure(0)); }

  // Ordinary network I/O failures are NORMAL.
  @Test public void connectExceptionIsNormal() {
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(new ConnectException("connection refused")));
  }
  @Test public void socketTimeoutIsNormal() {
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(new SocketTimeoutException("timeout")));
  }
  @Test public void ioExceptionIsNormal() {
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(new IOException("something else")));
  }

  // TLS / certificate validation failures are UNEXPECTED.
  @Test public void sslPeerUnverifiedIsUnexpected() {
    assertEquals(UNEXPECTED, HttpErrors.classifyTransportFailure(new SSLPeerUnverifiedException("peer not verified")));
  }
  @Test public void certificateExceptionIsUnexpected() {
    assertEquals(UNEXPECTED, HttpErrors.classifyTransportFailure(new CertificateException("cert invalid")));
  }
  @Test public void certificateExpiredIsUnexpected() {
    assertEquals(UNEXPECTED, HttpErrors.classifyTransportFailure(new CertificateExpiredException("expired")));
  }
  @Test public void certificateNotYetValidIsUnexpected() {
    assertEquals(UNEXPECTED,
        HttpErrors.classifyTransportFailure(new CertificateNotYetValidException("not yet valid")));
  }
  @Test public void certPathValidatorFailureIsUnexpected() {
    assertEquals(UNEXPECTED,
        HttpErrors.classifyTransportFailure(new CertPathValidatorException("path invalid")));
  }
  @Test public void certPathBuilderFailureIsUnexpected() {
    assertEquals(UNEXPECTED,
        HttpErrors.classifyTransportFailure(new CertPathBuilderException("cannot build path")));
  }

  @Test public void untrustedChainWrappedInHandshakeExceptionIsUnexpected() {
    SSLHandshakeException e = new SSLHandshakeException("PKIX path building failed");
    e.initCause(new CertPathBuilderException("unable to find valid certification path"));
    assertEquals(UNEXPECTED, HttpErrors.classifyTransportFailure(e));
  }

  @Test public void bareSslHandshakeFailureIsNormal() {
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(new SSLHandshakeException("handshake failed")));
  }
  @Test public void peerClosedMidHandshakeIsNormal() {
    SSLHandshakeException e = new SSLHandshakeException("Remote host terminated the handshake");
    e.initCause(new EOFException("SSL peer shut down incorrectly"));
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(e));
  }
  @Test public void sslExceptionFromConnectionResetIsNormal() {
    assertEquals(NORMAL, HttpErrors.classifyTransportFailure(new SSLException("Connection reset")));
  }

  // Cause-chain walk finds TLS deep in wrapper exceptions.
  @Test public void certificateCauseWrappedIsUnexpected() {
    IOException wrapper = new IOException("wrapped", new CertificateException("real cause"));
    assertEquals(UNEXPECTED, HttpErrors.classifyTransportFailure(wrapper));
  }
}
