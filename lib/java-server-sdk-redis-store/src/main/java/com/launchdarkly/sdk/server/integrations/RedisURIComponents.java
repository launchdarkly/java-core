package com.launchdarkly.sdk.server.integrations;

import java.net.URI;

/**
 * This class contains methods equivalent to those in JedisURIHelper. Avoiding the use of
 * JedisURIHelper allows us to be compatible with both Jedis 2.x and Jedis 3.x, because
 * that class doesn't exist in the same location in both versions.
 */
abstract class RedisURIComponents {
  static String getPassword(URI uri) {
    if (uri.getUserInfo() == null) {
      return null;
    }
    String[] parts = uri.getUserInfo().split(":", 2);
    return parts.length < 2 ? null : parts[1];
  }
  
  static int getDBIndex(URI uri) {
    String[] parts = uri.getPath().split("/", 2);
    if (parts.length < 2 || parts[1].isEmpty()) {
      return 0;
    }
    return Integer.parseInt(parts[1]);
  }
}
