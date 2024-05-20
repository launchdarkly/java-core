package com.launchdarkly.sdk.internal.http;

import java.net.URI;
import java.util.List;

import okhttp3.HttpUrl;

/**
 * Helper methods related to HTTP.
 * <p>
 * This class is for internal use only and should not be documented in the SDK API. It is not
 * supported for any use outside of the LaunchDarkly SDKs, and is subject to change without notice.
 */
public abstract class HttpHelpers {
  private HttpHelpers() {}

  /**
   * Safely concatenates a path, ensuring that there is exactly one slash between segments.
   * 
   * @param uri the URI
   * @param path the path to add
   * @return a new URI
   */
  public static URI concatenateUriPath(URI uri, String path) {
    HttpUrl.Builder concatBuilder = HttpUrl.get(uri).newBuilder();
    HttpUrl concatted = concatBuilder.addPathSegments(path).build();
    List<String> segments = concatted.pathSegments();

    // now remove empty segments. go in reverse to preserve indexes during modification
    HttpUrl.Builder sanitizedBuilder = concatted.newBuilder();
    for (int i = segments.size() - 1; i >= 0; i--) {
      if (segments.get(i).isEmpty()) {
        sanitizedBuilder.removePathSegment(i);
      }
    }
    return sanitizedBuilder.build().uri();
  }

  /**
   * Adds the query param to the URI.
   * 
   * @param uri the URI
   * @param name the name of the parameter
   * @param value the value of the parameter
   * @return the modified URI
   */
  public static URI addQueryParam(URI uri, String name, String value) {
    // it is important to use get(String) instead of get(URI) because get(String) will throw an exception
    // that includes useful information for the user to diagnose their URI.
    return HttpUrl.get(uri.toString()).newBuilder().addQueryParameter(name, value).build().uri();
}

  /**
   * Tests whether a string contains only characters that are safe to use in an HTTP header value.
   * <p>
   * This is specifically testing whether the string would be considered a valid HTTP header value
   * by the OkHttp client. The actual HTTP spec does not prohibit characters 127 and higher; OkHttp's
   * check is overly strict, as was pointed out in https://github.com/square/okhttp/issues/2016.
   * But all OkHttp 3.x and 4.x versions so far have continued to enforce that check. Control
   * characters other than a tab are always illegal.
   *
   * The value we're mainly concerned with is the SDK key (Authorization header). If an SDK key
   * accidentally has (for instance) a newline added to it, we don't want to end up having OkHttp
   * throw an exception mentioning the value, which might get logged (https://github.com/square/okhttp/issues/6738).
   * 
   * @param value a string
   * @return true if valid
   */
  public static boolean isAsciiHeaderValue(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if ((ch < 0x20 || ch > 0x7e) && ch != '\t') {
        return false;
      }
    }
    return true;
  }
  
}
