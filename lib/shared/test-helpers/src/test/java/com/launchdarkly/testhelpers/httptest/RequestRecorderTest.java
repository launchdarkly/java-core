package com.launchdarkly.testhelpers.httptest;

import org.junit.Test;

import java.net.URI;

import static com.launchdarkly.testhelpers.httptest.TestUtil.client;
import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@SuppressWarnings("javadoc")
public class RequestRecorderTest {
  // Note that these tests are really testing two things: the RequestRecorder API, and the
  // ability of the underlying server implementation to correctly get the request properties.
  
  @Test
  public void getMethodAndUri() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      URI requestedUri = server.getUri().resolve("/request/path");
      Response resp = client.newCall(
          new Request.Builder().url(requestedUri.toURL()).build()
          ).execute();

      assertThat(resp.code(), equalTo(200));
      
      RequestInfo received = server.getRecorder().requireRequest();
      assertThat(received.getMethod(), equalTo("GET"));
      assertThat(received.getUri(), equalTo(requestedUri));
      assertThat(received.getPath(), equalTo("/request/path"));
      assertThat(received.getQuery(), nullValue());
    }    
  }

  @Test
  public void queryString() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      URI requestedUri = server.getUri().resolve("/request/path?a=b");
      Response resp = client.newCall(
          new Request.Builder().url(requestedUri.toURL()).build()
          ).execute();

      assertThat(resp.code(), equalTo(200));
      
      RequestInfo received = server.getRecorder().requireRequest();
      assertThat(received.getMethod(), equalTo("GET"));
      assertThat(received.getUri(), equalTo(requestedUri));
      assertThat(received.getPath(), equalTo("/request/path"));
      assertThat(received.getQuery(), equalTo("?a=b"));
    }    
  }

  @Test
  public void requestHeaders() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      Response resp = client.newCall(
          new Request.Builder().url(server.getUri().toURL())
            .header("name1", "value1")
            .header("name2", "value2")
            .build()
          ).execute();

      assertThat(resp.code(), equalTo(200));
      
      RequestInfo received = server.getRecorder().requireRequest();
      assertThat(received.getHeader("name1"), equalTo("value1"));
      assertThat(received.getHeader("name2"), equalTo("value2"));
    }
  }

  @Test
  public void emptyRequestBodyByDefault() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      Response resp = client.newCall(
          new Request.Builder().url(server.getUri().toURL()).build()
          ).execute();

      assertThat(resp.code(), equalTo(200));
      
      RequestInfo received = server.getRecorder().requireRequest();
      assertThat(received.getBody(), equalTo(""));
    }
  }

  @Test
  public void patchRequestWithBody() throws Exception {
    doRequestWithBody("PATCH");
  }

  @Test
  public void postRequestWithBody() throws Exception {
    doRequestWithBody("POST");
  }

  @Test
  public void putRequestWithBody() throws Exception {
    doRequestWithBody("PUT");
  }

  @Test
  public void reportRequestWithBody() throws Exception {
    doRequestWithBody("REPORT");
  }

  private void doRequestWithBody(String method) throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      Response resp = client.newCall(
          new Request.Builder().url(server.getUri().toURL())
            .method(method, RequestBody.create("{}", MediaType.parse("application/json")))
            .build()
          ).execute();

      assertThat(resp.code(), equalTo(200));
      
      RequestInfo received = server.getRecorder().requireRequest();
      assertThat(received.getMethod(), equalTo(method));
      assertThat(received.getHeader("Content-Type"), startsWith("application/json"));
      assertThat(received.getBody(), equalTo("{}"));
    }
  }
  
  @Test
  public void canDisableRecorder() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      simpleGet(server.getUri().resolve("/path1"));
      
      server.getRecorder().setEnabled(false);
      
      simpleGet(server.getUri().resolve("/path2"));
      simpleGet(server.getUri().resolve("/path3"));
      
      server.getRecorder().setEnabled(true);
      
      simpleGet(server.getUri().resolve("/path4"));
      
      RequestInfo received1 = server.getRecorder().requireRequest();
      assertThat(received1.getPath(), equalTo("/path1"));

      RequestInfo received2 = server.getRecorder().requireRequest();
      assertThat(received2.getPath(), equalTo("/path4"));
      
      assertThat(server.getRecorder().count(), equalTo(0));
    }
  }
}
