package com.launchdarkly.testhelpers.httptest;

import org.junit.Test;

import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import okhttp3.Response;

@SuppressWarnings("javadoc")
public class SequentialHandlerTest {
  @Test
  public void handlersAreCalledInSequence() throws Exception {
    Handler handler = Handlers.sequential(Handlers.status(200), Handlers.status(201));
    
    try (HttpServer server = HttpServer.start(handler)) {
      Response resp1 = simpleGet(server.getUri());
      assertThat(resp1.code(), equalTo(200));
      
      Response resp2 = simpleGet(server.getUri());
      assertThat(resp2.code(), equalTo(201));
      
      Response resp3 = simpleGet(server.getUri());
      assertThat(resp3.code(), equalTo(500));
    }
  }
}
