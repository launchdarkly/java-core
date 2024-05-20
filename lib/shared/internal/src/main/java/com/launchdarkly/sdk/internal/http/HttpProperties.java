package com.launchdarkly.sdk.internal.http;

import java.net.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.OkHttpClient;

/**
 * Internal container for HTTP parameters used by SDK components. Includes logic for creating an
 * OkHttp client.
 * <p>
 * This is separate from any public HTTP configuration/builder classes that are part of the SDK API.
 * Those are transformed into this when the SDK is constructing components. The public API does not
 * reference any OkHttp classes, but this internal class does.  
 */
public final class HttpProperties {
  private static final int DEFAULT_TIMEOUT = 10000; // not used by the SDKs, just prevents invalid test conditions
  
  private final long connectTimeoutMillis;
  private final Map<String, String> defaultHeaders;
  private final HeadersTransformer headersTransformer;
  private final Proxy proxy;
  private final Authenticator proxyAuth;
  private final OkHttpClient sharedHttpClient;
   private final SocketFactory socketFactory;
  private final long socketTimeoutMillis;
  private final SSLSocketFactory sslSocketFactory;
  private final X509TrustManager trustManager;

  /**
   * Constructs an instance.
   * 
   * @param connectTimeoutMillis connection timeout milliseconds
   * @param defaultHeaders headers to add to all requests
   * @param headersTransformer optional callback to modify headers
   * @param proxy optional proxy
   * @param proxyAuth optional proxy authenticator
   * @param socketFactory optional socket factory
   * @param socketTimeoutMillis socket timeout milliseconds
   * @param sslSocketFactory optional SSL socket factory
   * @param trustManager optional SSL trust manager
   */
  public HttpProperties(
      long connectTimeoutMillis,
      Map<String, String> defaultHeaders,
      HeadersTransformer headersTransformer,
      Proxy proxy,
      Authenticator proxyAuth,
      SocketFactory socketFactory,
      long socketTimeoutMillis,
      SSLSocketFactory sslSocketFactory,
      X509TrustManager trustManager
      ) {
    super();
    this.connectTimeoutMillis = connectTimeoutMillis <= 0 ? DEFAULT_TIMEOUT : connectTimeoutMillis;
    this.defaultHeaders = defaultHeaders == null ? Collections.emptyMap() : new HashMap<>(defaultHeaders);
    this.headersTransformer = headersTransformer;
    this.proxy = proxy;
    this.proxyAuth = proxyAuth;
    this.sharedHttpClient = null;
    this.socketFactory = socketFactory;
    this.socketTimeoutMillis = socketTimeoutMillis <= 0 ? DEFAULT_TIMEOUT : socketTimeoutMillis;
    this.sslSocketFactory = sslSocketFactory;
    this.trustManager = trustManager;
  }

  /**
   * Constructs an instance with a preconfigured shared HTTP client.
   * 
   * @param sharedHttpClient an existing HTTP client instance
   * @param defaultHeaders headers to add to all requests
   * @param headersTransformer optional callback to modify headers
   */
  public HttpProperties(
      OkHttpClient sharedHttpClient,
      Map<String, String> defaultHeaders,
      HeadersTransformer headersTransformer
      ) {
    super();
    this.defaultHeaders = defaultHeaders == null ? Collections.emptyMap() : new HashMap<>(defaultHeaders);
    this.headersTransformer = headersTransformer;
    this.sharedHttpClient = sharedHttpClient;
    this.connectTimeoutMillis = DEFAULT_TIMEOUT;
    this.proxy = null;
    this.proxyAuth = null;
    this.socketFactory = null;
    this.socketTimeoutMillis = DEFAULT_TIMEOUT;
    this.sslSocketFactory = null;
    this.trustManager = null;
  }
  
  /**
   * Returns a minimal set of properties.
   * 
   * @return a default instance
   */
  public static HttpProperties defaults() {
    return new HttpProperties(0, null, null, null, null, null, 0, null, null);
  }
  
  /**
   * Returns an immutable view of the default headers. This does not include applying
   * the configured {@link HeadersTransformer}, if any.
   * 
   * @return the default headers
   * @see #toHeadersBuilder()
   */
  public Iterable<Map.Entry<String, String>> getDefaultHeaders() {
    return defaultHeaders.entrySet();
  }

  /**
   * Returns an immutable view of the headers to add to a request. This includes applying
   * the configured {@link HeadersTransformer}, if any.
   * 
   * @return the default headers
   * @see #toHeadersBuilder()
   */
  public Iterable<Map.Entry<String, String>> getTransformedDefaultHeaders() {
    if (headersTransformer == null) {
      return defaultHeaders.entrySet();
    }
    Map<String, String> ret = new HashMap<>(defaultHeaders);
    headersTransformer.updateHeaders(ret);
    return ret.entrySet();
  }

  /**
   * Returns the callback for transforming headers, if any.
   * 
   * @return a {@link HeadersTransformer} or null
   * @see #toHeadersBuilder()
   */
  public HeadersTransformer getHeadersTransformer() {
    return headersTransformer;
  }
  
  /**
   * Returns a preconfigured shared HTTP client, if one was defined.
   * <p>
   * SDK components that use {@link HttpProperties} should check this method first before
   * attempting to build their own client. If it returns a non-null value, they should use
   * that client; in that case, no other properties except the default headers are relevant,
   * and they should not take ownership of the client (that is, do not close the client when
   * the component is closed).
   * 
   * @return an HTTP client or null
   */
  public OkHttpClient getSharedHttpClient() {
    return sharedHttpClient;
  }
  
  /**
   * Applies the configured properties to an OkHttp client builder.
   * <p>
   * SDK components that use {@link HttpProperties} should check {@link #getSharedHttpClient()}
   * first before attempting to build their own client. The {@link #applyToHttpClientBuilder(okhttp3.OkHttpClient.Builder)}
   * method will not provide a correct configuration if a shared client was specified.
   * 
   * @param builder the client builder
   */
  public void applyToHttpClientBuilder(OkHttpClient.Builder builder) {
    builder.connectionPool(new ConnectionPool(5, 5, TimeUnit.SECONDS));
    if (connectTimeoutMillis > 0) {
      builder.connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS);
    }
    if (socketTimeoutMillis > 0) {
      builder.readTimeout(socketTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(socketTimeoutMillis, TimeUnit.MILLISECONDS);
    }
    builder.retryOnConnectionFailure(false); // we will implement our own retry logic
      
    if (socketFactory != null) {
      builder.socketFactory(socketFactory);
    }
  
    if (sslSocketFactory != null) {
      builder.sslSocketFactory(sslSocketFactory, trustManager);
    }
  
    if (proxy != null) {
      builder.proxy(proxy);
      if (proxyAuth != null) {
        builder.proxyAuthenticator(proxyAuth);
      }
    }
  }
  
  /**
   * Returns an OkHttp client builder initialized with the configured properties.
   * <p>
   * SDK components that use {@link HttpProperties} should check {@link #getSharedHttpClient()}
   * first before attempting to build their own client. The {@link #toHttpClientBuilder()} method
   * will not provide a correct configuration if a shared client was specified.
   * 
   * @return a client builder
   */
  public OkHttpClient.Builder toHttpClientBuilder() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    applyToHttpClientBuilder(builder);
    return builder;
  }
  
  /**
   * Returns an OkHttp Headers builder initialized with the default headers. This includes
   * calling the configured {@link HeadersTransformer}, if any.
   * 
   * @return a Headers builder
   */
  public Headers.Builder toHeadersBuilder() {
    Headers.Builder builder = new Headers.Builder();
    for (Map.Entry<String, String> kv: getTransformedDefaultHeaders()) {
      builder.add(kv.getKey(), kv.getValue());
    }
    return builder;
  }

  /**
   * Attempts to completely shut down an OkHttp client.
   * 
   * @param client the client to stop
   */
  public static void shutdownHttpClient(OkHttpClient client) {
    if (client.dispatcher() != null) {
      client.dispatcher().cancelAll();
      if (client.dispatcher().executorService() != null) {
        client.dispatcher().executorService().shutdown();
      }
    }
    if (client.connectionPool() != null) {
      client.connectionPool().evictAll();
    }
    if (client.cache() != null) {
      try {
        client.cache().close();
      } catch (Exception e) {}
    }
  }  
}
