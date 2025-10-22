package com.launchdarkly.testhelpers.httptest;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.testhelpers.httptest.TestUtil.client;
import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("javadoc")
public class HandlersTest {
  @Test
  public void status() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(419))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(419));
        assertThat(resp.header("Content-Type"), nullValue());
        assertThat(resp.body().string(), equalTo(""));
      }
    }
  }
  
  @Test
  public void header() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.header("header-name", "value"))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("Header-Name"), equalTo("value"));
      }
    }
  }
  
  @Test
  public void replaceHeader() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.all(
        Handlers.header("header-name", "old-value"),
        Handlers.header("header-name", "new-value")
        ))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.headers("Header-Name"), equalTo(ImmutableList.of("new-value")));
      }
    }
  }
  
  @Test
  public void addHeader() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.all(
        Handlers.addHeader("header-name", "old-value"),
        Handlers.addHeader("header-name", "new-value")
        ))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        // Not all HTTP server implementations are able to write multiple header lines. The HTTP spec
        // says that sending a comma-delimited list is exactly equivalent.
        assertThat(resp.headers("Header-Name"),
            anyOf(
                equalTo(ImmutableList.of("old-value", "new-value")),
                equalTo(ImmutableList.of("old-value,new-value"))
                ));
      }
    }
  }
  
  @Test
  public void body() throws Exception {
    byte[] data = new byte[] { 1, 2, 3 };
    try (HttpServer server = HttpServer.start(Handlers.body("application/weird", data))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.body().bytes(), equalTo(data));
      }
    }
  }
  
  @Test
  public void bodyStringWithNoCharset() throws Exception {
    String body = "hello";
    try (HttpServer server = HttpServer.start(Handlers.bodyString("text/weird", body))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("content-type"), equalTo("text/weird"));
        assertThat(resp.body().string(), equalTo(body));
      }
    }
  }
  
  @Test
  public void bodyStringWithCharset() throws Exception {
    String body = "hello";
    try (HttpServer server = HttpServer.start(Handlers.bodyString("text/weird", body, Charset.forName("UTF-8")))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("content-type"), equalTo("text/weird;charset=utf-8"));
        assertThat(resp.body().string(), equalTo(body));
      }
    }
  }

  @Test
  public void bodyJsonWithoutCharset() throws Exception {
    String body = "true";
    try (HttpServer server = HttpServer.start(Handlers.bodyJson(body))) {
      try (Response resp = client.newCall(new Request.Builder().url(server.getUrl()).build()).execute()) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("content-type"), equalTo("application/json"));
        assertThat(resp.body().string(), equalTo(body));
      }
    }
  }

  @Test
  public void bodyJsonWithCharset() throws Exception {
    String body = "true";
    try (HttpServer server = HttpServer.start(Handlers.bodyJson(body, Charset.forName("UTF-8")))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("content-type"), equalTo("application/json;charset=utf-8"));
        assertThat(resp.body().string(), equalTo(body));
      }
    }
  }
  
  @Test
  public void chainStatusAndHeadersAndBody() throws Exception {
    Handler handler = Handlers.all(
        Handlers.status(201),
        Handlers.header("name1", "value1"),
        Handlers.header("name2", "value2"),
        Handlers.bodyString("text/plain", "hello")
        );
    try (HttpServer server = HttpServer.start(handler)) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(201));
        assertThat(resp.header("name1"), equalTo("value1"));
        assertThat(resp.header("name2"), equalTo("value2"));
        assertThat(resp.header("content-type"), equalTo("text/plain"));
        assertThat(resp.body().string(), equalTo("hello"));
      }
    }
  }
  
  @Test
  public void waitFor() throws Exception {
    Semaphore signal = new Semaphore(0);
    Handler handler = Handlers.all(
        Handlers.waitFor(signal),
        Handlers.status(200)
        );
    try (HttpServer server = HttpServer.start(handler)) {
      AtomicBoolean signaled = new AtomicBoolean(false);
      new Thread(() -> {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        signaled.set(true);
        signal.release();
      }).start();
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(signaled.get(), equalTo(true));
        assertThat(resp.code(), equalTo(200));
      }
    }
  }
}
