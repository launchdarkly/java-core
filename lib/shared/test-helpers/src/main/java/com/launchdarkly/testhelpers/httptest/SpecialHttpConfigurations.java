package com.launchdarkly.testhelpers.httptest;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;

import javax.net.SocketFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Testing tools for validating that HTTP client logic is correctly applying configuration parameters.
 * The test methods set up a server with a defined behavior, then delegate to a provided test action
 * that is expected to succeed or fail depending on the test conditions.
 * 
 * @since 1.3.0
 */
public class SpecialHttpConfigurations {
  /**
   * See {@link TestAction}.
   */
  public static class Params {
    private final ServerTLSConfiguration tlsConfig;
    private final SocketFactory socketFactory;
    private final String proxyHost;
    private final int proxyPort;
    private final String proxyBasicAuthUser;
    private final String proxyBasicAuthPassword;
    
    Params(ServerTLSConfiguration tlsConfig, SocketFactory socketFactory, String proxyHost, int proxyPort,
        String proxyBasicAuthUser, String proxyBasicAuthPassword) {
      this.tlsConfig = tlsConfig;
      this.socketFactory = socketFactory;
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
      this.proxyBasicAuthUser = proxyBasicAuthUser;
      this.proxyBasicAuthPassword = proxyBasicAuthPassword;
    }

    /**
     * If the {@link ServerTLSConfiguration#getSocketFactory()} and {@link ServerTLSConfiguration#getTrustManager()}
     * properties are non-null, the {@link TestAction} should configure its HTTP client to use these
     * values for TLS configuration.
     * @return the TLS configuration
     */
    public ServerTLSConfiguration getTlsConfig() {
      return tlsConfig;
    }

    /**
     * If this property is non-null, the {@link TestAction} should configure its HTTP client to use it.
     * @return a custom socket factory
     */
    public SocketFactory getSocketFactory() {
      return socketFactory;
    }

    /**
     * If this property is non-null, the {@link TestAction} should configure its HTTP client to use a
     * web proxy with this hostname plus the other proxy properties.
     * @return the proxy hostname
     */
    public String getProxyHost() {
      return proxyHost;
    }

    /**
     * See {@link #getProxyHost()}.
     * @return the proxy port
     */
    public int getProxyPort() {
      return proxyPort;
    }

    /**
     * See {@link #getProxyHost()}.
     * @return the username for proxy basicauth or null
     */
    public String getProxyBasicAuthUser() {
      return proxyBasicAuthUser;
    }

    /**
     * See {@link #getProxyHost()}.
     * @return the password for proxy basicauth or null
     */
    public String getProxyBasicAuthPassword() {
      return proxyBasicAuthPassword;
    }
  }
  
  /**
   * See {@link TestAction}.
   */
  @SuppressWarnings("serial")
  public static class UnexpectedResponseException extends Exception {
    @SuppressWarnings("javadoc")
    public UnexpectedResponseException(String message) {
      super(message);
    }
  }
  
  /**
   * Implemented by the caller to perform some action against an HTTP server.
   */
  public interface TestAction {
    /**
     * Implement this method to perform whatever kind of HTTP client action you are testing.
     * The test framework has already set up a server with some predefined configuration, and has
     * provided the base URI of that server in {@code targetUri}. The properties in {@code params}
     * indicate how you should customize your HTTP client. The goal is to verify that 1. these
     * features (such as TLS and proxy configuration) work correctly in the client and 2. your
     * configuration logic is accurately transferring these parameters to the client.
     *
     * @param targetUri the URI to query
     * @param params client configuration options
     * @return true if successful; false if your client implementation does not support some of
     *   those parameters and you are deliberately skipping this test
     * @throws IOException if the connection failed
     * @throws UnexpectedResponseException if you were able to connect and send the request, but
     *   the content of the response was not consistent with the {@code handler} you passed to
     *   the test method
     */
    boolean doTest(URI targetUri, Params params) throws IOException, UnexpectedResponseException;
  }
  
  /**
   * Runs all of the other {@code test} methods with the same client logic.
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   */
  public static void testAll(
      Handler handler,
      TestAction testAction
      ) {
    testHttpClientDoesNotAllowSelfSignedCertByDefault(handler, testAction);
    testHttpClientCanBeConfiguredToAllowSelfSignedCert(handler, testAction);
    testHttpClientCanUseCustomSocketFactory(handler, testAction);
    testHttpClientCanUseProxy(handler, testAction);
    testHttpClientCanUseProxyWithBasicAuth(handler, testAction);
  }

  /**
   * Runs a test to verify that the HTTP client logic in the {@link TestAction} will fail if it
   * connects to an HTTPS endpoint that has a self-signed certificate, when it has not been
   * specifically configured to accept that certificate.
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   * @return true if successful; false if the {@link TestAction} returned false to indicate
   *   that the client does not support this kind of test
   */
  public static boolean testHttpClientDoesNotAllowSelfSignedCertByDefault(Handler handler,
      TestAction testAction) {
    Params params = new Params(null, null, null, 0, null, null);
    // deliberately don't include a TLS configuration in the Params, so the client doesn't know about the cert
    ServerTLSConfiguration tlsConfig = ServerTLSConfiguration.makeSelfSignedCertificate();
    try (HttpServer secureServer = HttpServer.startSecure(tlsConfig, handler)) {
      boolean didTest;
      try {
        didTest = testAction.doTest(secureServer.getUri(), params);
        // test was expected to throw an exception, so we should only get here if the test really didn't do anything
        assertThat("test action appears to have succeeded, but it should have failed", !didTest);
        return false;
      } catch (UnexpectedResponseException e) {
        assertThat("test action was able to get a response, even if its content was invalid; should have gotten an IOException", false);
      } catch (Exception e) { // any other kind of failure counts as an expected result
      }
      assertThat("expected the server not to receive a request due to TLS negotiation failure for a self-signed certificate",
          secureServer.getRecorder().count(), equalTo(0));
      return true;
    }
  }

  /**
   * Runs a test to verify that the HTTP client logic in the {@link TestAction} can be
   * configured to accept a specific HTTPS certificate (which in this case is self-signed).
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   * @return true if successful; false if the {@link TestAction} returned false to indicate
   *   that the client does not support this kind of test
   */
  public static boolean testHttpClientCanBeConfiguredToAllowSelfSignedCert(Handler handler,
      TestAction testAction) {
    String desc = "when the client was configured to accept a self-signed certificate";
    try {
      ServerTLSConfiguration tlsConfig = ServerTLSConfiguration.makeSelfSignedCertificate();
      Params params = new Params(tlsConfig, null, null, 0, null, null);
      try (HttpServer secureServer = HttpServer.startSecure(tlsConfig, handler)) {
        boolean didTest = testAction.doTest(secureServer.getUri(), params);
        if (didTest) {
          assertThat("expected the server to receive a request " + desc,
              secureServer.getRecorder().count(), equalTo(1));
        }
        return didTest;
      }
    } catch (Exception e) {
      throw unexpectedRequestFailure(e, desc);
    }
  }

  /**
   * Runs a test to verify that the HTTP client logic in the {@link TestAction} can be
   * configured to use a custom {@link SocketFactory}. The test method will provide a
   * deliberately incorrect URI, plus a custom socket factory that rewrites requests to go
   * to the correct URI, to verify that the socket factory is really being used.
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   * @return true if successful; false if the {@link TestAction} returned false to indicate
   *   that the client does not support this kind of test
   */
  public static boolean testHttpClientCanUseCustomSocketFactory(Handler handler,
      TestAction testAction) {
    String desc = "when the client was configured with a custom socket factory";
    try {
      try (HttpServer server = HttpServer.start(handler)) {
        Params params = new Params(null,
            makeSocketFactoryThatChangesHostAndPort(server.getUri().getHost(), server.getPort()),
            null, 0, null, null);
        URI uriWithWrongPort = URI.create("http://localhost:1");
        boolean didTest = testAction.doTest(uriWithWrongPort, params);
        if (didTest) {
          assertThat("expected the server to receive a request " + desc,
              server.getRecorder().count(), equalTo(1));
        }
        return didTest;
      }
    } catch (Exception e) {
      throw unexpectedRequestFailure(e, desc);
    }
  }
  
  /**
   * Runs a test to verify that the HTTP client logic in the {@link TestAction} can be
   * configured to use a web proxy. The test method will provide a deliberately incorrect
   * URI, plus the host/port of a fake proxy that accepts requests and returns the
   * configured response, to verify that the proxy settings are really being used.
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   * @return true if successful; false if the {@link TestAction} returned false to indicate
   *   that the client does not support this kind of test
   */
  public static boolean testHttpClientCanUseProxy(Handler handler,
      TestAction testAction) {
    String desc = "when the client was configured with a proxy";
    try {
      try (HttpServer server = HttpServer.start(handler)) {
        Params params = new Params(null, null, server.getUri().getHost(), server.getPort(), null, null);
        URI fakeBaseUri = URI.create("http://not-a-real-host");
        boolean didTest = testAction.doTest(fakeBaseUri, params);
        if (didTest) {
          assertThat("expected the server to receive a request " + desc,
              server.getRecorder().count(), equalTo(1));
        }
        return didTest;
      }
    } catch (Exception e) {
      throw unexpectedRequestFailure(e, desc);
    }
  }

  /**
   * Runs a test to verify that the HTTP client logic in the {@link TestAction} can be
   * configured to use a web proxy with basic authentication. The test method will provide
   * a deliberately incorrect URI, plus the host/port of a fake proxy that accepts requests
   * and returns the configured response, to verify that the proxy settings are really being
   * used; then it will verify that the fake proxy received the expected authorization header.
   * 
   * @param handler determines what the server should return for all responses
   * @param testAction a {@link TestAction} implementation
   * @return true if successful; false if the {@link TestAction} returned false to indicate
   *   that the client does not support this kind of test
   */
  public static boolean testHttpClientCanUseProxyWithBasicAuth(Handler handler,
      TestAction testAction) {
    String desc = "when the client was configured with a proxy with basicauth";
    Handler proxyHandler = ctx -> {
      if (ctx.getRequest().getHeader("Proxy-Authorization") == null) {
        ctx.setStatus(407);
        ctx.setHeader("Proxy-Authenticate", "Basic realm=x");
      } else {
        handler.apply(ctx);
      }
    };
    try {
      try (HttpServer server = HttpServer.start(proxyHandler)) {
        Params params = new Params(null, null, server.getUri().getHost(), server.getPort(), "user", "pass");
 
        URI fakeBaseUri = URI.create("http://not-a-real-host");
        boolean didTest = testAction.doTest(fakeBaseUri, params);
        if (didTest) {
          assertThat("expected the server to receive two requests " + desc,
              server.getRecorder().count(), equalTo(2));
          RequestInfo req1 = server.getRecorder().requireRequest();
          assertThat("expected the first request not to have a Proxy-Authorization header",
              req1.getHeader("Proxy-Authorization"), nullValue());
          RequestInfo req2 = server.getRecorder().requireRequest();
          assertThat("expected the second request (response to challenge) to have a valid Proxy-Authorization header",
              req2.getHeader("Proxy-Authorization"), equalTo("Basic dXNlcjpwYXNz"));
        }
        return didTest;
      }
    } catch (Exception e) {
      throw unexpectedRequestFailure(e, desc);
    }
  }
  
  private static AssertionError unexpectedRequestFailure(Exception e, String desc) {
    return new AssertionError("request failed " + desc + ": " + e);
  }
  
  /**
   * Creates a {@link SocketFactory} implementation that rewrites all requests to go to the
   * specified host and port, instead of the ones given in the URI. This is a simple way to
   * verify that a given piece of client logic is really using the configured socket factory.
   * 
   * @param host the hostname to send requests to
   * @param port the port to send requests to
   * @return a socket factory
   */
  public static SocketFactorySingleHost makeSocketFactoryThatChangesHostAndPort(String host, int port) {
    return new SocketFactorySingleHost(host, port);
  }
  
  private static final class SocketSingleHost extends Socket {
    private final String host;
    private final int port;

    SocketSingleHost(String host, int port) {
      this.host = host;
      this.port = port;
    }

    @Override public void connect(SocketAddress endpoint) throws IOException {
      super.connect(new InetSocketAddress(this.host, this.port), 0);
    }

    @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
      super.connect(new InetSocketAddress(this.host, this.port), timeout);
    }
  }

  static final class SocketFactorySingleHost extends SocketFactory {
    private final String host;
    private final int port;

    public SocketFactorySingleHost(String host, int port) {
      this.host = host;
      this.port = port;
    }

    @Override public Socket createSocket() throws IOException {
      return new SocketSingleHost(this.host, this.port);
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(this.host, this.port));
      return socket;
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(this.host, this.port));
      return socket;
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(this.host, this.port));
      return socket;
    }

    @Override public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(this.host, this.port));
      return socket;
    }
  }
}
