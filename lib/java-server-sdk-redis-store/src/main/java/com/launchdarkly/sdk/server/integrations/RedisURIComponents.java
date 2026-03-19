package com.launchdarkly.sdk.server.integrations;

import java.net.URI;

/**
 * This class contains methods equivalent to those in JedisURIHelper. Avoiding the use of
 * JedisURIHelper allows us to be compatible with both Jedis 2.x and Jedis 3.x, because
 * that class doesn't exist in the same location in both versions.
 */
abstract class RedisURIComponents {
  /**
   * Extracts the username from a Redis URI.
   * <p>
   * Supports both formats:
   * <ul>
   *   <li>{@code redis://USERNAME:PASSWORD@host:port} - returns USERNAME</li>
   *   <li>{@code redis://:PASSWORD@host:port} - returns null (password-only, legacy format)</li>
   * </ul>
   * 
   * @param uri the Redis URI
   * @return the username, or null if not specified or empty
   */
  static String getUsername(URI uri) {
    if (uri.getUserInfo() == null) {
      return null;
    }
    String[] parts = uri.getUserInfo().split(":", 2);
    // If the username part is empty (e.g., ":password"), return null
    return (parts.length > 0 && !parts[0].isEmpty()) ? parts[0] : null;
  }
  
  /**
   * Extracts the password from a Redis URI.
   * <p>
   * Supports both formats:
   * <ul>
   *   <li>{@code redis://USERNAME:PASSWORD@host:port} - returns PASSWORD</li>
   *   <li>{@code redis://:PASSWORD@host:port} - returns PASSWORD (legacy format)</li>
   * </ul>
   * 
   * @param uri the Redis URI
   * @return the password, or null if not specified
   */
  static String getPassword(URI uri) {
    if (uri.getUserInfo() == null) {
      return null;
    }
    String[] parts = uri.getUserInfo().split(":", 2);
    return parts.length < 2 ? null : parts[1];
  }
  
  /**
   * Extracts the database index from a Redis URI.
   * 
   * @param uri the Redis URI (e.g., {@code redis://host:port/2})
   * @return the database index, or 0 if not specified
   */
  static int getDBIndex(URI uri) {
    String[] parts = uri.getPath().split("/", 2);
    if (parts.length < 2 || parts[1].isEmpty()) {
      return 0;
    }
    return Integer.parseInt(parts[1]);
  }
}
