package com.launchdarkly.sdk.internal.http;

import java.util.Map;

/**
 * Callback interface for dynamically configuring HTTP headers on a per-request basis.
 */
public interface HeadersTransformer {
  /**
   * Transforms the headers that will be added to a request.
   *
   * @param headers The unmodified headers the SDK prepared for the request
   */
  void updateHeaders(Map<String, String> headers);
}
