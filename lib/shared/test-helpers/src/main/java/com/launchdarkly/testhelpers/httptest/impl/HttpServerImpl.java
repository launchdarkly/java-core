package com.launchdarkly.testhelpers.httptest.impl;

import com.launchdarkly.testhelpers.httptest.HttpServer;

/**
 * This class just contains the reference to the specific HTTP server implementation we will use,
 * so that HttpServer can contain only portable code.
 * 
 * @since 2.0.0
 */
public abstract class HttpServerImpl {
  private static final HttpServer.Delegate.Factory FACTORY =
      (port, handler, tlsConfig) -> new NanoHttpdServerDelegate(port, handler, tlsConfig);
  
  /**
   * Returns the implementation factory.
   * @return the factory
   */
  public static HttpServer.Delegate.Factory factory() {
    return FACTORY;
  }
}
