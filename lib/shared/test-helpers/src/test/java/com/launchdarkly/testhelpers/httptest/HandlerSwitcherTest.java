package com.launchdarkly.testhelpers.httptest;

import org.junit.Test;

import static com.launchdarkly.testhelpers.httptest.TestUtil.simpleGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import okhttp3.Response;

@SuppressWarnings("javadoc")
public class HandlerSwitcherTest {
  @Test
  public void switchHandlers() throws Exception {
    HandlerSwitcher switchable = new HandlerSwitcher(Handlers.status(200));
    
    try (HttpServer server = HttpServer.start(switchable)) {
      Response resp1 = simpleGet(server.getUri());
      assertThat(resp1.code(), equalTo(200));
      
      switchable.setTarget(Handlers.status(400));

      Response resp2 = simpleGet(server.getUri());
      assertThat(resp2.code(), equalTo(400));
    }
  }
}
