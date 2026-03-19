package com.launchdarkly.testhelpers.httptest;

/**
 * An abstraction used by {@link Handler} implementations to hide the details of
 * the underlying HTTP server framework.
 */
public interface RequestContext {
  /**
   * Returns the {@link RequestInfo}.
   * 
   * @return a {@link RequestInfo}
   */
  RequestInfo getRequest();
  
  /**
   * Sets the response status.
   * 
   * @param status the status code
   */
  void setStatus(int status);
  
  /**
   * Sets a response header.
   * 
   * @param name the header name
   * @param value the header value
   */
  void setHeader(String name, String value);

  /**
   * Adds a response header, without overwriting any previous values.
   * 
   * @param name the header name
   * @param value the header value
   */
  void addHeader(String name, String value);

  /**
   * Turns on chunked encoding.
   * <p>
   * It's only valid to call this when {@link #write(byte[])} has not yet been called. After
   * {@link #write(byte[])} is called, the behavior of {@link #setChunked()} is undefined.
   */
  void setChunked();
  
  /**
   * Writes data to the output stream.
   * 
   * @param data the data to write; null or zero-length data means to only flush the stream
   */
  void write(byte[] data);
  
  /**
   * Returns a path parameter, if any path parameters were captured.
   * <p>
   * By default, this will always return null. It is non-null only if you used
   * {@link SimpleRouter} and matched a regex pattern that was added with
   * {@link SimpleRouter#addRegex(java.util.regex.Pattern, Handler)}, and the pattern
   * contained capture groups. For instance, if the pattern was {@code /a/([^/]*)/c/(.*)}
   * and the request path was {@code /a/b/c/d/e}, {@code getPathParam(0)} would return
   * {@code "b"} and {@code getPathParam(1)} would return {@code "d/e"}.
   * 
   * @param i a zero-based positional index
   * @return the path parameter string; null if there were no path parameters, or if the index
   *   is out of range
   */
  String getPathParam(int i);
}
