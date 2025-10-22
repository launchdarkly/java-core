package com.launchdarkly.testhelpers.httptest;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

final class SequentialHandler implements Handler {
  private final Handler[] handlers;
  private final AtomicInteger index = new AtomicInteger(0);
  
  SequentialHandler(Handler[] handlers) {
    this.handlers = Arrays.copyOf(handlers, handlers.length);
  }
  
  @Override
  public void apply(RequestContext context) {
    int i = index.getAndIncrement();
    if (i >= handlers.length) {
      throw new RuntimeException("server received unexpected request");
    }
    handlers[i].apply(context);
  }
}
