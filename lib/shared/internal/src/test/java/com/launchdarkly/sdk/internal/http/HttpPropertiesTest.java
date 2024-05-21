package com.launchdarkly.sdk.internal.http;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.internal.BaseTest;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import okhttp3.Headers;
import okhttp3.OkHttpClient;

@SuppressWarnings("javadoc")
public class HttpPropertiesTest extends BaseTest {
  @Test
  public void testConnectTimeout() {
    HttpProperties hp = new HttpProperties(
        100000,
        null, null, null, null, null, 0, null, null);
    OkHttpClient httpClient = hp.toHttpClientBuilder().build();
    try {
      assertEquals(100000, httpClient.connectTimeoutMillis());
    } finally {
      HttpProperties.shutdownHttpClient(httpClient);
    }
  }
  
  @Test
  public void testSocketTimeout() {
    HttpProperties hp = new HttpProperties(
        0, null, null, null, null, null,
        100000,
        null, null);
    OkHttpClient httpClient = hp.toHttpClientBuilder().build();
    try {
      assertEquals(100000, httpClient.readTimeoutMillis());
    } finally {
      HttpProperties.shutdownHttpClient(httpClient);
    }
  }
  
  @Test
  public void testDefaultHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("name1", "value1");
    headers.put("name2", "value2");
    HttpProperties hp = new HttpProperties(
        0,
        headers,
        null, null, null, null, 0, null, null);
    
    Map<String, String> configured = ImmutableMap.copyOf(hp.getDefaultHeaders());
    assertEquals(headers, configured);
    
    Headers built = hp.toHeadersBuilder().build();
    assertEquals("value1", built.get("name1"));
    assertEquals("value2", built.get("name2"));
  }

  @Test
  public void testTransformedDefaultHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("name1", "value1");
    headers.put("name2", "value2");
    HeadersTransformer headersTransformer = new HeadersTransformer() {
      @Override
      public void updateHeaders(Map<String, String> h) {
        h.put("name1", h.get("name1") + "a");
      }
    };
    HttpProperties hp = new HttpProperties(
        0,
        headers, headersTransformer,
        null, null, null, 0, null, null);

    Map<String, String> configured = ImmutableMap.copyOf(hp.getDefaultHeaders());
    assertEquals(headers, configured);

    Map<String, String> transformed = ImmutableMap.copyOf(hp.getTransformedDefaultHeaders());
    assertEquals("value1a", transformed.get("name1"));
    assertEquals("value2", transformed.get("name2"));
    
    Headers built = hp.toHeadersBuilder().build();
    assertEquals("value1a", built.get("name1"));
    assertEquals("value2", built.get("name2"));
  }
  
  @Test
  public void testSharedHttpClient() {
    OkHttpClient httpClient = new OkHttpClient();
    HttpProperties hp = new HttpProperties(httpClient, null, null);
    assertSame(httpClient, hp.getSharedHttpClient());
  }
}
