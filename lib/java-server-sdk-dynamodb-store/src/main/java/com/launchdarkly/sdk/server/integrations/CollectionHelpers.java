package com.launchdarkly.sdk.server.integrations;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple factory methods that allow us to do without Guava in this library. We don't need immutable collections
 * for our simple use cases.
 */
final class CollectionHelpers {
  static <K, V> Map<K, V> mapOf(K k1, V v1) {
    return Collections.singletonMap(k1, v1);
  }

  static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
    Map<K, V> map = new HashMap<>(2);
    map.put(k1, v1);
    map.put(k2, v2);
    return map;
  }

  static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
    Map<K, V> map = new HashMap<>(2);
    map.put(k1, v1);
    map.put(k2, v2);
    map.put(k3, v3);
    return map;
  }

  static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
    Map<K, V> map = new HashMap<>(2);
    map.put(k1, v1);
    map.put(k2, v2);
    map.put(k3, v3);
    map.put(k4, v4);
    return map;
  }
}
