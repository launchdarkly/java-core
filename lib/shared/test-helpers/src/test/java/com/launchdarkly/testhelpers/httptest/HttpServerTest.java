package com.launchdarkly.testhelpers.httptest;

import org.junit.Test;

import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("javadoc")
public class HttpServerTest {
  @Test
  public void serverWithSimpleStatusHandler() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(419))) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(419));
      }
    }
  }
  
  @Test
  public void serverOnSpecificPort() throws Exception {
    try (HttpServer server = HttpServer.start(12345, Handlers.status(419))) {
      assertThat(server.getPort(), equalTo(12345));
      assertThat(server.getUri().toString(), equalTo("http://localhost:12345/"));
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(419));
      }
    }
  }
  
  @Test
  public void multipleServers() throws Exception {
    try (HttpServer server1 = HttpServer.start(Handlers.status(200))) {
      try (HttpServer server2 = HttpServer.start(Handlers.status(419))) {
        try (Response resp1 = simpleGet(server1.getUri())) {
          assertThat(resp1.code(), equalTo(200));          
        }
        try (Response resp2 = simpleGet(server2.getUri())) {
          assertThat(resp2.code(), equalTo(419));
        }
      }
    }
  }
  
  @Test
  public void serverReturns500StatusForExceptionFromHandler() throws Exception {
    Handler handler = ctx -> {
      throw new RuntimeException("unfortunate");
    };
    try (HttpServer server = HttpServer.start(handler)) {
      try (Response resp = simpleGet(server.getUri())) {
        assertThat(resp.code(), equalTo(500));
        assertThat(resp.body().string(), equalTo("java.lang.RuntimeException: unfortunate"));
      }
    }
  }
  
  @Test
  public void secureServerWithSelfSignedCert() throws Exception {
    ServerTLSConfiguration certData = ServerTLSConfiguration.makeSelfSignedCertificate();
    OkHttpClient client = new OkHttpClient.Builder()
        .sslSocketFactory(certData.getSocketFactory(), certData.getTrustManager())
        .build();

    try (HttpServer server = HttpServer.startSecure(certData, Handlers.status(419))) {
      assertThat(server.getUri().toString(), startsWith("https:"));
      
      try (Response resp = client.newCall(new Request.Builder().url(server.getUrl()).build()).execute()) {
        assertThat(resp.code(), equalTo(419));
      }
    }
  }
}
