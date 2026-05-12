package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.interfaces.ApplicationInfo;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.HttpConfiguration;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import static com.launchdarkly.sdk.server.TestUtil.getSdkVersion;
import static com.launchdarkly.sdk.server.integrations.HttpConfigurationBuilder.DEFAULT_CONNECT_TIMEOUT;
import static com.launchdarkly.sdk.server.integrations.HttpConfigurationBuilder.DEFAULT_SOCKET_TIMEOUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class HttpConfigurationBuilderTest {
  private static final String SDK_KEY = "sdk-key";
  private static final String INSTANCE_ID_HEADER = "X-LaunchDarkly-Instance-Id";
  private static final ClientContext BASIC_CONTEXT = new ClientContext(SDK_KEY);

  private static ImmutableMap.Builder<String, String> buildBasicHeaders() {
    return ImmutableMap.<String, String>builder()
        .put("Authorization", SDK_KEY)
        .put("User-Agent", "JavaClient/" + getSdkVersion());
  }

  /**
   * Returns a copy of the default headers from {@code hc} with the per-instance
   * {@code X-LaunchDarkly-Instance-Id} header removed, so the remainder can be compared against a
   * fixed expected map. The instance ID header is verified separately because its value is a
   * randomly generated UUID.
   */
  private static Map<String, String> headersExcludingInstanceId(HttpConfiguration hc) {
    Map<String, String> copy = new HashMap<>(ImmutableMap.copyOf(hc.getDefaultHeaders()));
    copy.remove(INSTANCE_ID_HEADER);
    return copy;
  }

  /**
   * Asserts that {@code hc} carries an {@code X-LaunchDarkly-Instance-Id} default header whose
   * value is a parseable v4 UUID, and returns that value so it can be compared across calls.
   */
  private static String assertHasInstanceIdHeader(HttpConfiguration hc) {
    String value = ImmutableMap.copyOf(hc.getDefaultHeaders()).get(INSTANCE_ID_HEADER);
    assertNotNull("expected X-LaunchDarkly-Instance-Id header to be present", value);
    UUID parsed = UUID.fromString(value);
    assertEquals("instance ID must be a UUID v4", 4, parsed.version());
    return value;
  }

  @Test
  public void testDefaults() {
    HttpConfiguration hc = Components.httpConfiguration().build(BASIC_CONTEXT);
    assertEquals(DEFAULT_CONNECT_TIMEOUT, hc.getConnectTimeout());
    assertNull(hc.getProxy());
    assertNull(hc.getProxyAuthentication());
    assertEquals(DEFAULT_SOCKET_TIMEOUT, hc.getSocketTimeout());
    assertNull(hc.getSocketFactory());
    assertNull(hc.getSslSocketFactory());
    assertNull(hc.getTrustManager());
    assertEquals(buildBasicHeaders().build(), headersExcludingInstanceId(hc));
    assertHasInstanceIdHeader(hc);
  }

  @Test
  public void testCanSetCustomHeaders() {
      HttpConfiguration hc = Components.httpConfiguration()
              .addCustomHeader("X-LaunchDarkly-Test-Label", "my-cool-label")
              .addCustomHeader("X-Header-Message", "Java FTW")
              .addCustomHeader("Authorization", "I can override this")
              .addCustomHeader("User-Agent", "This too")
              .build(BASIC_CONTEXT);

      ImmutableMap<String, String> expectedHeaders = ImmutableMap.<String, String>builder()
              .put("X-LaunchDarkly-Test-Label", "my-cool-label")
              .put("X-Header-Message", "Java FTW")
              .put("Authorization", "I can override this")
              .put("User-Agent", "This too")
              .build();

      assertEquals(expectedHeaders, headersExcludingInstanceId(hc));
      assertHasInstanceIdHeader(hc);
  }

  @Test
  public void testInstanceIdHeaderIsUuidV4() {
    HttpConfiguration hc = Components.httpConfiguration().build(BASIC_CONTEXT);
    assertHasInstanceIdHeader(hc);
  }

  @Test
  public void testInstanceIdIsDifferentBetweenHttpConfigurations() {
    // Each call to build() represents a new SDK instance; each must get its own GUID.
    HttpConfiguration hc1 = Components.httpConfiguration().build(BASIC_CONTEXT);
    HttpConfiguration hc2 = Components.httpConfiguration().build(BASIC_CONTEXT);
    String id1 = assertHasInstanceIdHeader(hc1);
    String id2 = assertHasInstanceIdHeader(hc2);
    assertNotEquals("each SDK instance should generate its own instance id", id1, id2);
  }

  @Test
  public void testInstanceIdHeaderIsNotOverriddenByCustomHeaders() {
    // The default-headers map is built once per HttpConfiguration; a user-supplied custom header
    // for X-LaunchDarkly-Instance-Id is allowed to replace the SDK-generated value, but absent
    // that, the SDK's generated UUID must come through.
    HttpConfiguration hc = Components.httpConfiguration()
        .addCustomHeader("X-Some-Other-Header", "value")
        .build(BASIC_CONTEXT);
    String value = assertHasInstanceIdHeader(hc);
    assertTrue("instance ID must not be the literal string 'X-Some-Other-Header' value",
        !"value".equals(value));
  }

  @Test
  public void testConnectTimeout() {
    HttpConfiguration hc = Components.httpConfiguration()
        .connectTimeout(Duration.ofMillis(999))
        .build(BASIC_CONTEXT);
    assertEquals(999, hc.getConnectTimeout().toMillis());

    HttpConfiguration hc2 = Components.httpConfiguration()
        .connectTimeout(Duration.ofMillis(999))
        .connectTimeout(null)
        .build(BASIC_CONTEXT);
    assertEquals(DEFAULT_CONNECT_TIMEOUT, hc2.getConnectTimeout());
}

  @Test
  public void testProxy() {
    HttpConfiguration hc = Components.httpConfiguration()
        .proxyHostAndPort("my-proxy", 1234)
        .build(BASIC_CONTEXT);
    assertEquals(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("my-proxy", 1234)), hc.getProxy());
    assertNull(hc.getProxyAuthentication());
  }

  @Test
  public void testProxyBasicAuth() {
    HttpConfiguration hc = Components.httpConfiguration()
        .proxyHostAndPort("my-proxy", 1234)
        .proxyAuth(Components.httpBasicAuthentication("user", "pass"))
        .build(BASIC_CONTEXT);
    assertEquals(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("my-proxy", 1234)), hc.getProxy());
    assertNotNull(hc.getProxyAuthentication());
    assertEquals("Basic dXNlcjpwYXNz", hc.getProxyAuthentication().provideAuthorization(null));
  }

  @Test
  public void testSocketTimeout() {
    HttpConfiguration hc1 = Components.httpConfiguration()
        .socketTimeout(Duration.ofMillis(999))
        .build(BASIC_CONTEXT);
    assertEquals(999, hc1.getSocketTimeout().toMillis());

    HttpConfiguration hc2 = Components.httpConfiguration()
        .socketTimeout(Duration.ofMillis(999))
        .socketTimeout(null)
        .build(BASIC_CONTEXT);
    assertEquals(DEFAULT_SOCKET_TIMEOUT, hc2.getSocketTimeout());
  }

  @Test
  public void testSocketFactory() {
    SocketFactory sf = new StubSocketFactory();
    HttpConfiguration hc = Components.httpConfiguration()
        .socketFactory(sf)
        .build(BASIC_CONTEXT);
    assertSame(sf, hc.getSocketFactory());
  }

  @Test
  public void testSslOptions() {
    SSLSocketFactory sf = new StubSSLSocketFactory();
    X509TrustManager tm = new StubX509TrustManager();
    HttpConfiguration hc = Components.httpConfiguration()
        .sslSocketFactory(sf, tm)
        .build(BASIC_CONTEXT);
    assertSame(sf, hc.getSslSocketFactory());
    assertSame(tm, hc.getTrustManager());
  }

  @Test
  public void testWrapperNameOnly() {
    HttpConfiguration hc = Components.httpConfiguration()
        .wrapper("Scala", null)
        .build(BASIC_CONTEXT);
    assertEquals("Scala", ImmutableMap.copyOf(hc.getDefaultHeaders()).get("X-LaunchDarkly-Wrapper"));
  }

  @Test
  public void testWrapperWithVersion() {
    HttpConfiguration hc = Components.httpConfiguration()
        .wrapper("Scala", "0.1.0")
        .build(BASIC_CONTEXT);
    assertEquals("Scala/0.1.0", ImmutableMap.copyOf(hc.getDefaultHeaders()).get("X-LaunchDarkly-Wrapper"));
  }

  @Test
  public void testApplicationTags() {
    ApplicationInfo info = new ApplicationInfo("authentication-service", "1.0.0");
    ClientContext contextWithTags = new ClientContext(SDK_KEY, info, null, null, false, null, 0, null);
    HttpConfiguration hc = Components.httpConfiguration()
        .build(contextWithTags);
    assertEquals("application-id/authentication-service application-version/1.0.0", ImmutableMap.copyOf(hc.getDefaultHeaders()).get("X-LaunchDarkly-Tags"));
  }

  public static class StubSocketFactory extends SocketFactory {
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
        throws IOException {
      return null;
    }

    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException, UnknownHostException {
      return null;
    }

    public Socket createSocket(InetAddress host, int port) throws IOException {
      return null;
    }

    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
      return null;
    }

    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
      return null;
    }
  }

  public static class StubSSLSocketFactory extends SSLSocketFactory {
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
        throws IOException {
      return null;
    }

    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException, UnknownHostException {
      return null;
    }

    public Socket createSocket(InetAddress host, int port) throws IOException {
      return null;
    }

    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
      return null;
    }

    public String[] getSupportedCipherSuites() {
      return null;
    }

    public String[] getDefaultCipherSuites() {
      return null;
    }

    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
      return null;
    }
  }

  public static class StubX509TrustManager implements X509TrustManager {
    public X509Certificate[] getAcceptedIssuers() {
      return null;
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
  }
}
