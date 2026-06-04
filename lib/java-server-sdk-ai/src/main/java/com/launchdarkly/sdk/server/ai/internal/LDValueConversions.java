package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal helpers for converting {@link LDValue} instances to and from the plain Java objects
 * (maps, lists, strings, numbers, booleans) understood by the Mustache renderer.
 * <p>
 * This class is for internal use only and is not part of the supported public API.
 */
public final class LDValueConversions {
  private LDValueConversions() {
  }

  /**
   * Converts an {@link LDValue} into a plain Java object tree.
   *
   * @param value the value to convert (may be {@code null})
   * @return {@code null}, a {@link Boolean}, a {@link Long} or {@link Double}, a {@link String},
   *     a {@link List}, or a {@link Map} depending on the value's JSON type
   */
  public static Object toPlainObject(LDValue value) {
    if (value == null || value.isNull()) {
      return null;
    }
    switch (value.getType()) {
      case BOOLEAN:
        return value.booleanValue();
      case NUMBER:
        return numberToPlainObject(value);
      case STRING:
        return value.stringValue();
      case ARRAY: {
        List<Object> list = new ArrayList<>(value.size());
        for (LDValue element : value.values()) {
          list.add(toPlainObject(element));
        }
        return list;
      }
      case OBJECT: {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : value.keys()) {
          map.put(key, toPlainObject(value.get(key)));
        }
        return map;
      }
      default:
        return null;
    }
  }

  private static Object numberToPlainObject(LDValue value) {
    double doubleValue = value.doubleValue();
    // Render whole numbers without a trailing ".0" so that templates such as "{{count}}" match the
    // behavior of the Python reference SDK (which preserves integers).
    if (doubleValue == Math.rint(doubleValue) && !Double.isInfinite(doubleValue)) {
      return (long) doubleValue;
    }
    return doubleValue;
  }
}
