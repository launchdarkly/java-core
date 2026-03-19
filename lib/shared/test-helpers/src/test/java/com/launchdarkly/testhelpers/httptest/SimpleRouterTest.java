package com.launchdarkly.testhelpers.httptest;

import org.junit.Test;

import java.util.regex.Pattern;

import static com.launchdarkly.testhelpers.httptest.TestUtil.client;
import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@SuppressWarnings("javadoc")
public class SimpleRouterTest {
  @Test
  public void noPathsMatchByDefault() throws Exception {
    SimpleRouter router = new SimpleRouter();
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp = simpleGet(server.getUri());
      assertThat(resp.code(), equalTo(404));
    }
  }
  
  @Test
  public void simplePathMatch() throws Exception {
    SimpleRouter router = new SimpleRouter();
    router.add("/path1", Handlers.status(201));
    router.add("/path2", Handlers.status(419));
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp1 = simpleGet(server.getUri().resolve("/path1"));
      assertThat(resp1.code(), equalTo(201));

      Response resp2 = simpleGet(server.getUri().resolve("/path2"));
      assertThat(resp2.code(), equalTo(419));

      Response resp3 = simpleGet(server.getUri().resolve("/path3"));
      assertThat(resp3.code(), equalTo(404));
    }
  }
  
  @Test
  public void simplePathMatchWithMethod() throws Exception {
    SimpleRouter router = new SimpleRouter();
    router.add("GET", "/path1", Handlers.status(201));
    router.add("DELETE", "/path1", Handlers.status(204));
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp1 = simpleGet(server.getUri().resolve("/path1"));
      assertThat(resp1.code(), equalTo(201));

      Response resp2 = client.newCall(new Request.Builder().url(server.getUri().resolve("/path1").toURL())
          .method("DELETE", null).build()).execute();
      assertThat(resp2.code(), equalTo(204));
    }
  }

  @Test
  public void pathRegexMatch() throws Exception {
    SimpleRouter router = new SimpleRouter();
    router.addRegex(Pattern.compile("/path[12]"), Handlers.status(201));
    router.addRegex(Pattern.compile("/path[34]"), Handlers.status(419));
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp1 = simpleGet(server.getUri().resolve("/path1"));
      assertThat(resp1.code(), equalTo(201));

      Response resp2 = simpleGet(server.getUri().resolve("/path3"));
      assertThat(resp2.code(), equalTo(419));

      Response resp3 = simpleGet(server.getUri().resolve("/path5"));
      assertThat(resp3.code(), equalTo(404));
    }
  }

  @Test
  public void pathRegexMatchWithMethod() throws Exception {
    SimpleRouter router = new SimpleRouter();
    router.addRegex("GET", Pattern.compile("/path[12]"), Handlers.status(201));
    router.addRegex("DELETE", Pattern.compile("/path[12]"), Handlers.status(419));
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp1 = simpleGet(server.getUri().resolve("/path1"));
      assertThat(resp1.code(), equalTo(201));

      Response resp2 = client.newCall(new Request.Builder().url(server.getUri().resolve("/path1").toURL())
          .method("DELETE", null).build()).execute();
      assertThat(resp2.code(), equalTo(419));

      Response resp3 = client.newCall(new Request.Builder().url(server.getUri().resolve("/path1").toURL())
          .method("POST", RequestBody.create(new byte[0])).build()).execute();
      assertThat(resp3.code(), equalTo(405));

      Response resp4 = client.newCall(new Request.Builder().url(server.getUri().resolve("/path3").toURL())
          .method("DELETE", null).build()).execute();
      assertThat(resp4.code(), equalTo(404));
    }
  }

  @Test
  public void pathRegexMatchWithPathParam() throws Exception {
    SimpleRouter router = new SimpleRouter();
    router.addRegex(Pattern.compile("/path1/([^/]*)/do/(.*)"), ctx -> {
      String message = "I did " + ctx.getPathParam(1) + " in " + ctx.getPathParam(0);
      Handlers.bodyString("text/plain", message).apply(ctx);
    });
    
    try (HttpServer server = HttpServer.start(router)) {
      Response resp1 = simpleGet(server.getUri().resolve("/path1/Chicago/do/something/or/other"));
      assertThat(resp1.code(), equalTo(200));
      assertThat(resp1.body().string(), equalTo("I did something/or/other in Chicago"));
    }
  }  
}
