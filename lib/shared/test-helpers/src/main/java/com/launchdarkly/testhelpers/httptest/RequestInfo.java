package com.launchdarkly.testhelpers.httptest;

import com.google.common.collect.ImmutableMap;

import java.net.URI;

/**
 * Properties of a request received by {@link HttpServer}.
 * <p>
 * We capture all of the request properties, including the request body, before passing the request
 * to the configured handler, because tests often need to record and inspect the request.
 */
public final class RequestInfo {
  private final String method;
  private final URI uri;
  private final String path;
  private final String query;
  private final ImmutableMap<String, String> headers;
  private final String body;
  
  /**
   * Constructs an instance, specifying all properties.
   * 
   * @param method the HTTP method
   * @param uri the URI
   * @param path the request path
   * @param query the query string
   * @param headers the headers
   * @param body the body, or null
   */
  public RequestInfo(String method, URI uri, String path, String query,
      ImmutableMap<String, String> headers, String body) {
    this.method = method.toUpperCase();
    this.uri = uri;
    this.path = path;
    this.query = query;
    this.headers = headers == null ? ImmutableMap.of() : headers;
    this.body = body;
  }
  
  /**
   * Returns the HTTP method.
   * 
   * @return the HTTP method
   */
  public String getMethod() {
    return method;
  }

  /**
   * Returns the full request URI.
   * 
   * @return the request URI
   */
  public URI getUri() {
    return uri;
  }
  
  /**
   * Returns the request path.
   * 
   * @return the path
   */
  public String getPath() {
    return path;
  }

  /**
   * Returns the request query string.
   * 
   * @return the query string (including the leading "?"), or null if there is none
   */
  public String getQuery() {
    return query;
  }
  
  /**
   * Returns a request header by name.
   * 
   * @param name a case-insensitive header name
   * @return the header value, or null if not found
   */
  public String getHeader(String name) {
    return headers.get(name.toLowerCase()); 
  }

  /**
   * Returns all request header names.
   * 
   * @return the header names
   */
  public Iterable<String> getHeaderNames() {
    return headers.keySet();
  }
  
  /**
   * Returns the request body as a string.
   * 
   * @return the request body, or null if there is none
   */
  public String getBody() {
    return body;
  }
}
